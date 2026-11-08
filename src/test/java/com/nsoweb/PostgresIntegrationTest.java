package com.nsoweb;

import com.google.gson.JsonParser;
import com.nsoweb.config.AppConfig;
import com.nsoweb.db.*;
import com.nsoweb.net.WebServer;
import com.nsoweb.util.SecurityUtil;
import com.nsoweb.world.WorldManager;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only. Runs in a newly generated schema and removes only that test schema. */
@EnabledIfSystemProperty(named="nso.test.postgres", matches="true")
class PostgresIntegrationTest {
    static class Listener implements WebSocket.Listener {
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicInteger pongs = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final StringBuilder partial = new StringBuilder();
        volatile long pingStarted;
        final List<Long> latencies = new CopyOnWriteArrayList<>();
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if(last) {
                var p=JsonParser.parseString(partial.toString()).getAsJsonObject(); partial.setLength(0);
                String type=p.get("type").getAsString();
                if(type.equals("map_enter")) entered.countDown();
                if(type.equals("pong")) {pongs.incrementAndGet();latencies.add((System.nanoTime()-pingStarted)/1_000_000);}
                if(type.equals("error")) errors.incrementAndGet();
            }
            ws.request(1); return null;
        }
        @Override public void onError(WebSocket ws,Throwable t){errors.incrementAndGet();}
    }
    @Test void fiveLivePlayersWithRealPostgresAndScheduledSaves() throws Exception {
        var base=AppConfig.fromEnv();
        assertFalse(base.databaseUrl.startsWith("jdbc:h2:"));
        String schema="nso_review_"+UUID.randomUUID().toString().replace("-","");
        assertTrue(schema.matches("nso_review_[a-f0-9]{32}"));
        try(DbManager admin=new DbManager(base)) {
            try(var conn=admin.getConnection();var st=conn.createStatement()) {st.execute("CREATE SCHEMA "+schema);}
            try {
                var root=Paths.get("").toAbsolutePath();
                String url=base.databaseUrl+(base.databaseUrl.contains("?")?"&":"?")+"currentSchema="+schema;
                var cfg=AppConfig.forTest(url,root.resolve("data"),root.resolve("sql"));
                try(DbManager db=new DbManager(cfg)) {
                    db.initializeAndSeedIfNeeded(cfg);
                    var sd=new StaticDataRepository().loadAll(db,cfg);
                    assertEquals(177,sd.tileMaps.size());
                    var repo=new PlayerRepository(db);
                    var world=new WorldManager(cfg,sd,repo);
                    var server=new WebServer(cfg,sd,repo,world);
                    world.start(); server.start();
                    List<WebSocket> sockets=new ArrayList<>();
                    List<Listener> listeners=new ArrayList<>();
                    try {
                        var http=HttpClient.newHttpClient();
                        for(int i=0;i<5;i++) {
                            var user=repo.registerUser("smoke_user_"+i,"test1234");
                            long pid=repo.createCharacter(user.userId,"smoke_char_"+i,0,11,1,sd).id;
                            var listener=new Listener();listeners.add(listener);
                            var ws=http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+server.getPort()
                                    +"/ws?token="+SecurityUtil.issueToken(user.userId,user.username)+"&charId="+pid),listener).join();
                            sockets.add(ws);assertTrue(listener.entered.await(5,TimeUnit.SECONDS));
                        }
                        for(int tick=0;tick<500;tick++) {
                            for(int i=0;i<5;i++) {
                                sockets.get(i).sendText("{\"type\":\"move\",\"x\":"+(230+tick%100)+",\"y\":192}",true).join();
                                if(tick%10==0) {
                                    listeners.get(i).pingStarted=System.nanoTime();
                                    sockets.get(i).sendText("{\"type\":\"ping\"}",true).join();
                                }
                            }
                            Thread.sleep(100);
                        }
                        assertEquals(250,listeners.stream().mapToInt(l->l.pongs.get()).sum());
                        assertEquals(0,listeners.stream().mapToInt(l->l.errors.get()).sum());
                        List<Long> rtt=new ArrayList<>();listeners.forEach(l->rtt.addAll(l.latencies));Collections.sort(rtt);
                        try(var conn=db.getConnection();var st=conn.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM players WHERE online=TRUE")) {
                            rs.next();assertEquals(5,rs.getInt(1),"45-second autosave persisted every player");
                        }
                        // A real PostgreSQL failure after the first UPDATE must roll it back.
                        var states=world.onlinePlayers.values().stream().map(c->c.getPlayer()).collect(java.util.stream.Collectors.toList());
                        var first=states.get(0);var second=states.get(1);
                        long savedXu=repo.loadPlayer(first.id,first.userId,sd).xu;
                        var failing=new PlayerRepository(db) {
                            @Override protected void writePlayer(java.sql.Connection c,com.nsoweb.model.PlayerState p,boolean online) throws java.sql.SQLException {
                                super.writePlayer(c,p,online);
                                if(p.id==second.id) throw new java.sql.SQLException("Injected transaction failure");
                            }
                        };
                        synchronized(world) {
                            first.xu-=100;
                            assertThrows(java.sql.SQLException.class,()->failing.savePlayersAtomically(List.of(first,second),true));
                            first.xu+=100;
                        }
                        assertEquals(savedXu,repo.loadPlayer(first.id,first.userId,sd).xu);
                        System.out.println("POSTGRES SMOKE: 5 players; 2500 moves; 250 pongs; p95="+rtt.get(237)
                                +"ms; max="+rtt.get(rtt.size()-1)+"ms; autosave and rollback OK");
                    } finally {
                        for(var ws:sockets) ws.sendClose(WebSocket.NORMAL_CLOSURE,"test complete").join();
                        world.shutdown();server.stop();
                    }
                    try(var conn=db.getConnection();var st=conn.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM players WHERE online=FALSE")) {
                        rs.next();assertEquals(5,rs.getInt(1),"Disconnect saves must complete");
                    }
                }
            } finally {
                try(var conn=admin.getConnection();var st=conn.createStatement()) {st.execute("DROP SCHEMA "+schema+" CASCADE");}
            }
        }
    }
}
