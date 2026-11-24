package net.rose.saturnProtect;

import net.rose.saturnProtect.api.SaturnProtectAPI;
import net.rose.saturnProtect.api.SaturnProtectApiImpl;
import net.rose.saturnProtect.command.CommandRegistrar;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.InspectorManager;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.core.LookupService;
import net.rose.saturnProtect.core.PurgeService;
import net.rose.saturnProtect.core.RollbackService;
import net.rose.saturnProtect.db.Database;
import net.rose.saturnProtect.listener.BlockListener;
import net.rose.saturnProtect.listener.ChatListener;
import net.rose.saturnProtect.listener.ContainerListener;
import net.rose.saturnProtect.listener.EntityBlockListener;
import net.rose.saturnProtect.listener.EnvironmentListener;
import net.rose.saturnProtect.listener.FrameStandListener;
import net.rose.saturnProtect.listener.InspectListener;
import net.rose.saturnProtect.listener.ItemListener;
import net.rose.saturnProtect.listener.KillListener;
import net.rose.saturnProtect.listener.PistonListener;
import net.rose.saturnProtect.listener.SessionListener;
import net.rose.saturnProtect.listener.SignListener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SaturnProtect extends JavaPlugin {

    private ProtectConfig protectConfig;
    private Database database;
    private LogService logService;
    private InspectorManager inspectors;
    private LookupService lookups;
    private RollbackService rollbacks;
    private PurgeService purges;
    private SaturnProtectAPI api;

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
        purges = new PurgeService(this, database);

        api = new SaturnProtectApiImpl(database, logService, rollbacks);
        getServer().getServicesManager().register(SaturnProtectAPI.class, api, this, ServicePriority.Normal);

        registerListeners();
        CommandRegistrar.register(this);

        getLogger().info("SaturnProtect enabled");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
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
        manager.registerEvents(new SessionListener(protectConfig, logService), this);
        manager.registerEvents(new ChatListener(protectConfig, logService), this);
        manager.registerEvents(new KillListener(protectConfig, logService), this);
        manager.registerEvents(new SignListener(protectConfig, logService), this);
        manager.registerEvents(new EnvironmentListener(this, protectConfig, logService), this);
        manager.registerEvents(new PistonListener(protectConfig, logService), this);
        manager.registerEvents(new EntityBlockListener(protectConfig, logService), this);
        manager.registerEvents(new FrameStandListener(protectConfig, logService), this);
    }

    /**
     * Re-reads config.yml. Listeners and services read the config on every event, so almost everything takes
     * effect straight away. Only {@code database.file} needs a restart; the flush interval is re-scheduled here.
     */
    public void reload() {
        reloadConfig();
        protectConfig.reload(getConfig());
        database.reloadSettings();
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

    public PurgeService purges() {
        return purges;
    }

    /** The same API other plugins get from the services manager. */
    public SaturnProtectAPI api() {
        return api;
    }
}
