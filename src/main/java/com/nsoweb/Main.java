package com.nsoweb;

import com.nsoweb.config.AppConfig;
import com.nsoweb.db.DbManager;
import com.nsoweb.db.PlayerRepository;
import com.nsoweb.db.StaticDataRepository;
import com.nsoweb.model.StaticData;
import com.nsoweb.net.WebServer;
import com.nsoweb.world.WorldManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        AppConfig config = AppConfig.fromEnv();

        DbManager db = new DbManager(config);
        if (config.autoInitDb) {
            db.initializeAndSeedIfNeeded(config);
        }

        StaticData staticData = new StaticDataRepository().loadAll(db, config);
        PlayerRepository playerRepo = new PlayerRepository(db);
        WorldManager world = new WorldManager(config, staticData, playerRepo);
        world.start();

        WebServer server = new WebServer(config, staticData, playerRepo, world);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down NSO Web server...");
            world.shutdown();
            server.stop();
            db.close();
        }, "nso-shutdown"));

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        log.info("NSO Web ready in {} ms | Port={} | RAM used={} MB / max={} MB",
                System.currentTimeMillis() - t0, server.getPort(), usedMb, rt.maxMemory() / (1024 * 1024));
    }
}
