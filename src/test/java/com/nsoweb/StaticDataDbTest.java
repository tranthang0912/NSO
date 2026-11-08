package com.nsoweb;

import com.nsoweb.config.AppConfig;
import com.nsoweb.db.DbManager;
import com.nsoweb.db.StaticDataRepository;
import com.nsoweb.model.StaticData;
import com.nsoweb.model.StaticData.TileMapData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class StaticDataDbTest {

    @Test
    void schemaAndSeedFollowPurePostgresAndLoadIntoRam() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Path sqlDir = root.resolve("sql");
        Path dataDir = root.resolve("data");

        String schemaSql = Files.readString(sqlDir.resolve("schema.postgres.sql"), StandardCharsets.UTF_8);
        String seedSql = Files.readString(dataDir.resolve("seed_static.sql"), StandardCharsets.UTF_8);

        // Kiểm tra nghiêm ngặt không còn cú pháp MySQL
        assertFalse(schemaSql.contains("`"), "Schema must not contain MySQL backticks");
        assertFalse(schemaSql.toUpperCase().contains("AUTO_INCREMENT"), "Schema must not use AUTO_INCREMENT");
        assertFalse(schemaSql.toUpperCase().contains("ENGINE="), "Schema must not use ENGINE=");
        assertFalse(seedSql.contains("`"), "Seed SQL must not contain MySQL backticks");
        assertFalse(seedSql.toUpperCase().contains("LIMIT 1"), "Seed SQL must not contain MySQL UPDATE LIMIT 1");

        // Kiểm tra thực thi schema + seed trên H2 PostgreSQL compatibility mode
        String h2Url = "jdbc:h2:mem:nso_step1_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        AppConfig cfg = AppConfig.forTest(h2Url, dataDir, sqlDir);
        try (DbManager db = new DbManager(cfg)) {
            db.initializeAndSeedIfNeeded(cfg);
            StaticData sd = new StaticDataRepository().loadAll(db, cfg);

            assertEquals(7, sd.clazzMap.size(), "Phải có 7 môn phái (0..6)");
            assertEquals(177, sd.tileMaps.size(), "Phải có đủ 177 bản đồ");
            assertEquals(258, sd.monsters.size(), "Phải có 258 quái vật");
            assertEquals(43, sd.npcs.size(), "Phải có 43 NPC");
            assertEquals(1085, sd.items.size(), "Phải có 1085 vật phẩm");
            assertEquals(204, sd.itemOptions.size(), "Phải có 204 item_option");
            assertEquals(91, sd.skillTemplates.size(), "Phải có 91 skill_template");
            assertEquals(282, sd.njPartsRaw.size(), "Phải có 282 nj_part");
            assertEquals(23, sd.stores.size(), "Phải có 23 cửa hàng");
            assertEquals(43, sd.tasks.size(), "Phải có 43 nhiệm vụ");
            assertTrue(sd.exps.length >= 100, "Bảng EXP phải có >= 100 cấp");

            // Kiểm tra giải mã bản đồ 22 (Làng Tone), 139 (Quỷ Sơn có locationStand), 176 (map cuối)
            TileMapData tone = sd.tileMaps.get(22);
            assertNotNull(tone);
            assertEquals("Làng Tone", tone.name);
            assertTrue(tone.tmw > 10 && tone.tmh > 10);
            assertEquals(tone.tmw * tone.tmh, tone.tiles.length);
            assertEquals(tone.tmw * tone.tmh, tone.types.length);

            TileMapData quySon = sd.tileMaps.get(139);
            assertNotNull(quySon);
            assertEquals(60, quySon.tmw);
            assertEquals(42, quySon.tmh);

            TileMapData lastMap = sd.tileMaps.get(176);
            assertNotNull(lastMap);
            assertEquals(30, lastMap.tmw);
            assertEquals(30, lastMap.tmh);
        }
    }
}
