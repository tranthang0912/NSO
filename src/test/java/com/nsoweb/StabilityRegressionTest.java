package com.nsoweb;

import com.nsoweb.config.AppConfig;
import com.nsoweb.db.*;
import com.nsoweb.model.*;
import com.nsoweb.net.ClientSession;
import com.nsoweb.world.*;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class StabilityRegressionTest {
    DbManager db;
    FailingRepository repo;
    StaticData sd;
    WorldManager world;
    AppConfig config;
    static class FailingRepository extends PlayerRepository {
        long failId = -1;
        FailingRepository(DbManager db) { super(db); }
        @Override protected void writePlayer(Connection conn, PlayerState p, boolean online) throws SQLException {
            if (p.id == failId) throw new SQLException("Injected database failure");
            super.writePlayer(conn, p, online);
        }
    }
    static class RecordingClient extends ClientSession {
        final List<Map<String,Object>> packets = new ArrayList<>();
        RecordingClient(PlayerState p, WorldManager world) { super(null, p, world); }
        @Override public void sendJson(Map<String,Object> packet) { packets.add(packet); }
    }
    @BeforeEach void setup() throws Exception {
        var root = Paths.get("").toAbsolutePath();
        config = AppConfig.forTest("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                root.resolve("data"), root.resolve("sql"));
        db = new DbManager(config);
        db.initializeAndSeedIfNeeded(config);
        sd = new StaticDataRepository().loadAll(db, config);
        repo = new FailingRepository(db);
        world = new WorldManager(config, sd, repo);
    }
    @AfterEach void cleanup() {
        repo.failId = -1;
        world.shutdown();
        db.close();
    }
    RecordingClient player(String name) throws Exception {
        long uid = repo.registerUser(name, "test1234").userId;
        long pid = repo.createCharacter(uid, name, 0, 11, 1, sd).id;
        return world.openSession(uid, pid, p -> new RecordingClient(p, world));
    }
    void trade(RecordingClient a, RecordingClient b, long xu, List<Integer> indices) {
        world.endgame.tradeInvite(a,b.getPlayerId());
        world.endgame.tradeAccept(b,a.getPlayerId());
        world.endgame.tradeUpdate(a,xu,indices);
        world.endgame.tradeLock(a);
        world.endgame.tradeLock(b);
    }
    @Test void onlyOneActiveCharacterPerAccountAndNoStaleSessionCommands() throws Exception {
        var a = player("session_a");
        long uid=a.getPlayer().userId;
        long second = repo.createCharacter(uid,"second_char",0,11,1,sd).id;
        a.onMessage("{\"type\":\"exchange_luong\",\"option\":1}");
        assertThrows(IllegalArgumentException.class, () -> world.openSession(uid,second,p -> new RecordingClient(p,world)));
        assertThrows(IllegalArgumentException.class, () -> world.openSession(uid,a.getPlayerId(),p -> new RecordingClient(p,world)));
        world.leaveWorld(a);
        var next = world.openSession(uid,second,p -> new RecordingClient(p,world));
        assertEquals(490,next.getPlayer().luong);
        a.onMessage("{\"type\":\"teleport_map\",\"mapId\":1}");
        assertNull(world.getZoneFor(a.getPlayerId()));
        assertEquals(22,next.getPlayer().mapId);
        assertEquals(105000,repo.loadPlayer(a.getPlayerId(),uid,sd).xu);
    }
    @Test void tradeBlocksInventoryMutationAndTransfersTheDisplayedItem() throws Exception {
        var a=player("trade_a"); var b=player("trade_b");
        a.getPlayer().bag=new ArrayList<>(List.of(new ItemInstance(13,1),new ItemInstance(0,1)));
        b.getPlayer().bag.clear();
        trade(a,b,0,List.of(0));
        a.onMessage("{\"type\":\"sort_bag\"}");
        a.onMessage("{\"type\":\"drop_bag_item\",\"bagIdx\":0}");
        assertEquals(13,a.getPlayer().bag.get(0).itemId);
        world.endgame.tradeConfirm(a); world.endgame.tradeConfirm(b);
        assertEquals(13,b.getPlayer().bag.get(0).itemId);
    }
    @Test void changedOfferIsRejectedEvenIfMutatedOutsidePacketHandler() throws Exception {
        var a=player("changed_a"); var b=player("changed_b");
        trade(a,b,100,List.of(0));
        a.getPlayer().bag.get(0).quantity--;
        world.endgame.tradeConfirm(a); world.endgame.tradeConfirm(b);
        assertEquals(5000,a.getPlayer().xu);
        assertFalse(world.endgame.isTrading(a.getPlayerId()));
        assertFalse(b.packets.stream().anyMatch(p -> "trade_closed".equals(p.get("type")) && Boolean.TRUE.equals(p.get("ok"))));
    }
    @Test void failedSecondTradeSaveRollsBackBothPlayersAndLiveState() throws Exception {
        var a=player("rollback_a"); var b=player("rollback_b");
        trade(a,b,100,List.of(0));
        repo.failId=b.getPlayerId();
        world.endgame.tradeConfirm(a); world.endgame.tradeConfirm(b);
        assertEquals(5000,repo.loadPlayer(a.getPlayerId(),a.getPlayer().userId,sd).xu);
        assertEquals(5000,repo.loadPlayer(b.getPlayerId(),b.getPlayer().userId,sd).xu);
        assertEquals(5000,a.getPlayer().xu);
        assertEquals(5000,b.getPlayer().xu);
        assertEquals(4,a.getPlayer().bag.size());
        assertFalse(b.packets.stream().anyMatch(p -> "trade_closed".equals(p.get("type")) && Boolean.TRUE.equals(p.get("ok"))));
    }
    @Test void failedDisconnectSaveIsRetriedBeforeNextLogin() throws Exception {
        var a=player("retry_a");
        a.getPlayer().xu+=1234;
        repo.failId=a.getPlayerId();
        world.leaveWorld(a);
        assertThrows(SQLException.class, () -> world.openSession(a.getPlayer().userId,a.getPlayerId(),p -> new RecordingClient(p,world)));
        repo.failId=-1;
        var next=world.openSession(a.getPlayer().userId,a.getPlayerId(),p -> new RecordingClient(p,world));
        assertEquals(6234,next.getPlayer().xu);
    }
    @Test void shopUsesOriginalNsoResaleRulesAndCannotMintYenFromPotions() throws Exception {
        var a=player("shop_a");
        a.getPlayer().bag.clear();
        long before=a.getPlayer().yen;
        a.onMessage("{\"type\":\"buy_item\",\"storeId\":7,\"index\":1,\"quantity\":1}");
        a.onMessage("{\"type\":\"sell_item\",\"bagIdx\":0,\"quantity\":1}");
        assertEquals(before-300,a.getPlayer().yen);
        assertEquals(0,PlayerOps.resaleYen(sd.items.get(14)));
        var weapon=sd.items.get(94);
        assertEquals((weapon.level/10*100==0?60:weapon.level/10*100)*5,PlayerOps.resaleYen(weapon));
        var upgraded=new ItemInstance(94,1); upgraded.upgrade=1;
        a.getPlayer().bag.add(upgraded);
        a.onMessage("{\"type\":\"sell_item\",\"bagIdx\":0,\"quantity\":1}");
        assertSame(upgraded,a.getPlayer().bag.get(0));
    }
    @Test void upgradeLimitsMatchOriginalItemTemplate() {
        int[] levels={1,9,10,19,20,39,40,49,50,59,60,100};
        int[] caps={0,0,4,4,8,8,12,12,14,14,16,16};
        for(int i=0;i<levels.length;i++) assertEquals(caps[i],EndgameOps.maxUpgradeForLevel(levels[i]));
    }
    @Test void oldAutosaveSnapshotCannotUndoNewerCommittedBalanceOrDisconnect() throws Exception {
        var a=player("snapshot_a");
        var old=repo.snapshot(a.getPlayer());
        a.getPlayer().xu+=900;
        repo.savePlayer(a.getPlayer(),false);
        repo.saveSnapshot(old,true);
        assertEquals(5900,repo.loadPlayer(a.getPlayerId(),a.getPlayer().userId,sd).xu);
        try(var conn=db.getConnection();var ps=conn.prepareStatement("SELECT online FROM players WHERE id=?")) {
            ps.setLong(1,a.getPlayerId());
            try(var rs=ps.executeQuery()){rs.next();assertFalse(rs.getBoolean(1));}
        }
    }
    @Test void scriptFailureRollsBackAndDoesNotCommitCallersTransaction() throws Exception {
        try(var conn=db.getConnection();var st=conn.createStatement()) {
            st.execute("CREATE TABLE seed_probe(id INT PRIMARY KEY)");
            assertThrows(SQLException.class, () -> SqlScriptRunner.executeScript(conn,
                    "BEGIN; INSERT INTO seed_probe VALUES(1); INSERT INTO no_such_table VALUES(2); COMMIT;"));
            try(var rs=st.executeQuery("SELECT COUNT(*) FROM seed_probe")){rs.next();assertEquals(0,rs.getInt(1));}
            conn.setAutoCommit(false);
            SqlScriptRunner.executeScript(conn,"INSERT INTO seed_probe VALUES(2);");
            conn.rollback();
            try(var rs=st.executeQuery("SELECT COUNT(*) FROM seed_probe")){rs.next();assertEquals(0,rs.getInt(1));}
        }
    }
    @Test void clanCreationAndDonationPersistPlayerDebitWithClanCredit() throws Exception {
        var a=player("clan_a");
        var beforeClan=repo.snapshot(a.getPlayer());
        world.endgame.createClan(a,world.getZoneFor(a.getPlayerId()),"ReviewClan");
        repo.saveSnapshot(beforeClan,true);
        assertEquals(3000,repo.loadPlayer(a.getPlayerId(),a.getPlayer().userId,sd).xu);
        assertNotNull(repo.loadPlayer(a.getPlayerId(),a.getPlayer().userId,sd).clanId);
        var beforeDonation=repo.snapshot(a.getPlayer());
        world.endgame.clanDonate(a,500);
        repo.saveSnapshot(beforeDonation,true);
        assertEquals(2500,repo.loadPlayer(a.getPlayerId(),a.getPlayer().userId,sd).xu);
        assertEquals(500,world.clanRepo.loadClan(a.getPlayer().clanId,Set.of()).coin);
    }
    @Test void clanLeaderCannotKickAnotherClansMember() throws Exception {
        var a=player("leader_a"); var b=player("leader_b"); var c=player("member_c");
        world.endgame.createClan(a,world.getZoneFor(a.getPlayerId()),"ClanA");
        world.endgame.createClan(b,world.getZoneFor(b.getPlayerId()),"ClanB");
        world.clanRepo.addMember(b.getPlayer().clanId,"ClanB",c.getPlayer());
        world.endgame.clanKick(a,c.getPlayerId());
        assertEquals(b.getPlayer().clanId,repo.loadPlayer(c.getPlayerId(),c.getPlayer().userId,sd).clanId);
    }
}
