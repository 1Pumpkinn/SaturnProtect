package net.rose.saturnProtect;

import net.rose.saturnProtect.command.CommandRegistrar;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.InspectorManager;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.core.LookupService;
import net.rose.saturnProtect.core.RollbackService;
import net.rose.saturnProtect.db.Database;
import net.rose.saturnProtect.listener.BlockListener;
import net.rose.saturnProtect.listener.ContainerListener;
import net.rose.saturnProtect.listener.InspectListener;
import net.rose.saturnProtect.listener.ItemListener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SaturnProtect extends JavaPlugin {

    private ProtectConfig protectConfig;
    private Database database;
    private LogService logService;
    private InspectorManager inspectors;
    private LookupService lookups;
    private RollbackService rollbacks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        protectConfig = new ProtectConfig(getConfig());
        Permissions.register();

        database = new Database(this, protectConfig);
        try {
            database.start();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Could not open the database, disabling SaturnProtect", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        logService = new LogService(protectConfig, database);
        inspectors = new InspectorManager();
        lookups = new LookupService(this, protectConfig, database);
        rollbacks = new RollbackService(this, protectConfig, database);

        registerListeners();
        CommandRegistrar.register(this);

        getLogger().info("SaturnProtect enabled");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
    }

    private void registerListeners() {
        PluginManager manager = getServer().getPluginManager();
        manager.registerEvents(new BlockListener(this, protectConfig, logService), this);
        manager.registerEvents(new InspectListener(this, protectConfig, database, inspectors), this);
        manager.registerEvents(new ContainerListener(protectConfig, logService), this);
        manager.registerEvents(new ItemListener(protectConfig, logService), this);
    }

    public ProtectConfig protectConfig() {
        return protectConfig;
    }

    public Database database() {
        return database;
    }

    public LogService logService() {
        return logService;
    }

    public InspectorManager inspectors() {
        return inspectors;
    }

    public LookupService lookups() {
        return lookups;
    }

    public RollbackService rollbacks() {
        return rollbacks;
    }
}
