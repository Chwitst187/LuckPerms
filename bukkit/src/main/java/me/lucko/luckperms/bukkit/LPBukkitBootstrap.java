/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.bukkit;

import me.lucko.luckperms.bukkit.util.NullSafeConsoleCommandSender;
import me.lucko.luckperms.common.loader.LoaderBootstrap;
import me.lucko.luckperms.common.plugin.bootstrap.BootstrappedWithLoader;
import me.lucko.luckperms.common.plugin.bootstrap.LuckPermsBootstrap;
import me.lucko.luckperms.common.plugin.classpath.ClassPathAppender;
import me.lucko.luckperms.common.plugin.classpath.JarInJarClassPathAppender;
import me.lucko.luckperms.common.plugin.logging.JavaPluginLogger;
import me.lucko.luckperms.common.plugin.logging.PluginLogger;
import net.luckperms.api.platform.Platform;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Bootstrap plugin for LuckPerms running on Bukkit.
 */
public class LPBukkitBootstrap implements LuckPermsBootstrap, LoaderBootstrap, BootstrappedWithLoader {
    private final JavaPlugin loader;

    /**
     * The plugin logger
     */
    private final PluginLogger logger;

    /**
     * A scheduler adapter for the platform
     */
    private final BukkitSchedulerAdapter schedulerAdapter;

    /**
     * The plugin class path appender
     */
    private final ClassPathAppender classPathAppender;

    /**
     * A null-safe console instance which delegates to the server logger
     * if {@link Server#getConsoleSender()} returns null.
     */
    private final ConsoleCommandSender console;

    /**
     * The plugin instance
     */
    private final LPBukkitPlugin plugin;

    /**
     * The time when the plugin was enabled
     */
    private Instant startTime;

    // load/enable latches
    private final CountDownLatch loadLatch = new CountDownLatch(1);
    private final CountDownLatch enableLatch = new CountDownLatch(1);
    private boolean serverStarting = true;
    private boolean serverStopping = false;

    // if the plugin has been loaded on an incompatible version
    private boolean incompatibleVersion = false;

    private final boolean folia = detectFolia();

    public LPBukkitBootstrap(JavaPlugin loader) {
        this.loader = loader;

        this.logger = new JavaPluginLogger(loader.getLogger());
        this.schedulerAdapter = new BukkitSchedulerAdapter(this);
        this.classPathAppender = new JarInJarClassPathAppender(getClass().getClassLoader());
        this.console = new NullSafeConsoleCommandSender(getServer());
        this.plugin = new LPBukkitPlugin(this);
    }

    // provide adapters

    @Override
    public JavaPlugin getLoader() {
        return this.loader;
    }

    public Server getServer() {
        return this.loader.getServer();
    }

    public boolean isFolia() {
        return this.folia;
    }

    @Override
    public PluginLogger getPluginLogger() {
        return this.logger;
    }

    @Override
    public BukkitSchedulerAdapter getScheduler() {
        return this.schedulerAdapter;
    }

    @Override
    public ClassPathAppender getClassPathAppender() {
        return this.classPathAppender;
    }

    public ConsoleCommandSender getConsole() {
        return this.console;
    }

    public void executeSync(Runnable task) {
        if (this.folia) {
            try {
                Object scheduler = getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(getServer());
                Method executeMethod = scheduler.getClass().getMethod("execute", org.bukkit.plugin.Plugin.class, Runnable.class);
                executeMethod.invoke(scheduler, this.loader, task);
                return;
            } catch (ReflectiveOperationException e) {
                this.logger.warn("Failed to execute task on Folia global region scheduler, falling back to Bukkit scheduler.", e);
            }
        }

        getServer().getScheduler().scheduleSyncDelayedTask(this.loader, task);
    }

    public void executeAsync(Runnable task) {
        if (this.folia) {
            try {
                Object scheduler = getServer().getClass().getMethod("getAsyncScheduler").invoke(getServer());
                Method runNowMethod = scheduler.getClass().getMethod("runNow", org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class);
                runNowMethod.invoke(scheduler, this.loader, consumer(task));
                return;
            } catch (ReflectiveOperationException e) {
                this.logger.warn("Failed to execute task on Folia async scheduler, falling back to Bukkit scheduler.", e);
            }
        }

        getServer().getScheduler().runTaskAsynchronously(this.loader, task);
    }

    public void runAsyncLater(Runnable task, long ticksDelay) {
        if (this.folia) {
            try {
                Object scheduler = getServer().getClass().getMethod("getAsyncScheduler").invoke(getServer());
                Method runDelayedMethod = scheduler.getClass().getMethod("runDelayed", org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, long.class, TimeUnit.class);
                runDelayedMethod.invoke(scheduler, this.loader, consumer(task), ticksDelay * 50L, TimeUnit.MILLISECONDS);
                return;
            } catch (ReflectiveOperationException e) {
                this.logger.warn("Failed to delay task on Folia async scheduler, falling back to Bukkit scheduler.", e);
            }
        }

        getServer().getScheduler().runTaskLaterAsynchronously(this.loader, task, ticksDelay);
    }

    public void runSyncLater(Runnable task, long ticksDelay) {
        if (this.folia) {
            try {
                Object scheduler = getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(getServer());
                Method runDelayedMethod = scheduler.getClass().getMethod("runDelayed", org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, long.class);
                runDelayedMethod.invoke(scheduler, this.loader, consumer(task), ticksDelay);
                return;
            } catch (ReflectiveOperationException e) {
                this.logger.warn("Failed to delay task on Folia global region scheduler, falling back to Bukkit scheduler.", e);
            }
        }

        getServer().getScheduler().runTaskLater(this.loader, task, ticksDelay);
    }

    public void runEntityTaskLater(Player player, Runnable task, long ticksDelay) {
        if (this.folia) {
            try {
                Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
                Method runDelayedMethod = scheduler.getClass().getMethod("runDelayed", org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, Runnable.class, long.class);
                runDelayedMethod.invoke(scheduler, this.loader, consumer(task), null, ticksDelay);
                return;
            } catch (ReflectiveOperationException e) {
                this.logger.warn("Failed to delay task on Folia entity scheduler, falling back to Bukkit scheduler.", e);
            }
        }

        getServer().getScheduler().runTaskLater(this.loader, task, ticksDelay);
    }

    private static java.util.function.Consumer<Object> consumer(Runnable task) {
        return ignored -> task.run();
    }

    // lifecycle

    @Override
    public void onLoad() {
        if (checkIncompatibleVersion()) {
            this.incompatibleVersion = true;
            return;
        }
        try {
            this.plugin.load();
        } finally {
            this.loadLatch.countDown();
        }
    }

    @Override
    public void onEnable() {
        if (this.incompatibleVersion) {
            Logger logger = this.loader.getLogger();
            logger.severe("----------------------------------------------------------------------");
            logger.severe("Your server version is not compatible with this build of LuckPerms. :(");
            logger.severe("");
            logger.severe("If your server is running 1.8, please update to 1.8.8 or higher.");
            logger.severe("If your server is running 1.7.10, please download the Bukkit-Legacy version of LuckPerms from here:");
            logger.severe("==> https://luckperms.net/download");
            logger.severe("----------------------------------------------------------------------");
            getServer().getPluginManager().disablePlugin(this.loader);
            return;
        }

        this.serverStarting = true;
        this.serverStopping = false;
        this.startTime = Instant.now();

        if (this.folia) {
            this.loader.getLogger().severe("Folia Support is extremly experimental. Dont expect it to work correctly");
        }

        try {
            this.plugin.enable();

            // schedule a task to update the 'serverStarting' flag
            runSyncLater(() -> this.serverStarting = false, 1L);
        } finally {
            this.enableLatch.countDown();
        }
    }

    @Override
    public void onDisable() {
        if (this.incompatibleVersion) {
            return;
        }

        this.serverStopping = true;
        this.plugin.disable();
    }

    @Override
    public CountDownLatch getEnableLatch() {
        return this.enableLatch;
    }

    @Override
    public CountDownLatch getLoadLatch() {
        return this.loadLatch;
    }

    public boolean isServerStarting() {
        return this.serverStarting;
    }

    public boolean isServerStopping() {
        return this.serverStopping;
    }

    // provide information about the plugin

    @Override
    public String getVersion() {
        return this.loader.getDescription().getVersion();
    }

    @Override
    public Instant getStartupTime() {
        return this.startTime;
    }

    // provide information about the platform

    @Override
    public Platform.Type getType() {
        return Platform.Type.BUKKIT;
    }

    @Override
    public String getServerBrand() {
        return getServer().getName();
    }

    @Override
    public String getServerVersion() {
        return getServer().getVersion() + " - " + getServer().getBukkitVersion();
    }

    @Override
    public Path getDataDirectory() {
        return this.loader.getDataFolder().toPath().toAbsolutePath();
    }

    @Override
    public Optional<Player> getPlayer(UUID uniqueId) {
        return Optional.ofNullable(getServer().getPlayer(uniqueId));
    }

    @Override
    public Optional<UUID> lookupUniqueId(String username) {
        //noinspection deprecation
        return Optional.ofNullable(getServer().getOfflinePlayer(username)).map(OfflinePlayer::getUniqueId);
    }

    @Override
    public Optional<String> lookupUsername(UUID uniqueId) {
        return Optional.ofNullable(getServer().getOfflinePlayer(uniqueId)).map(OfflinePlayer::getName);
    }

    @Override
    public int getPlayerCount() {
        return getServer().getOnlinePlayers().size();
    }

    @Override
    public Collection<String> getPlayerList() {
        Collection<? extends Player> players = getServer().getOnlinePlayers();
        List<String> list = new ArrayList<>(players.size());
        for (Player player : players) {
            list.add(player.getName());
        }
        return list;
    }

    @Override
    public Collection<UUID> getOnlinePlayers() {
        Collection<? extends Player> players = getServer().getOnlinePlayers();
        List<UUID> list = new ArrayList<>(players.size());
        for (Player player : players) {
            list.add(player.getUniqueId());
        }
        return list;
    }

    @Override
    public boolean isPlayerOnline(UUID uniqueId) {
        Player player = getServer().getPlayer(uniqueId);
        return player != null && player.isOnline();
    }

    @Override
    public @Nullable String identifyClassLoader(ClassLoader classLoader) throws ReflectiveOperationException {
        Class<?> pluginClassLoaderClass = Class.forName("org.bukkit.plugin.java.PluginClassLoader");
        if (pluginClassLoaderClass.isInstance(classLoader)) {
            Method getPluginMethod = pluginClassLoaderClass.getDeclaredMethod("getPlugin");
            getPluginMethod.setAccessible(true);

            JavaPlugin plugin = (JavaPlugin) getPluginMethod.invoke(classLoader);
            return plugin.getName();
        }
        return null;
    }

    private static boolean checkIncompatibleVersion() {
        try {
            Class.forName("com.google.gson.JsonElement");
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
