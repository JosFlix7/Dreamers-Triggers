package dev.dreamers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

/**
 * Habla el protocolo 2 de Shulker Box Tooltip para que el cliente pueda
 * previsualizar el cofre de ender, que no viaja en el ítem.
 */
final class TooltipServer implements Listener, PluginMessageListener {
    private static final String HANDSHAKE_IN = "shulkerboxtooltip:c2s_handshake";
    private static final String REQUEST_IN = "shulkerboxtooltip:ec_update_req";
    private static final String HANDSHAKE_OUT = "shulkerboxtooltip:s2c_handshake";
    private static final String UPDATE_OUT = "shulkerboxtooltip:ec_update";
    private static final int PROTOCOL_MAJOR = 2;
    private static final int PROTOCOL_MINOR = 0;
    private static final long DEBOUNCE_MS = 1000L;

    private final JavaPlugin plugin;
    private final Set<UUID> clients = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> pending = new ConcurrentHashMap<>();
    private final NbtPackets packets = new NbtPackets();
    private byte[] handshake;

    TooltipServer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, HANDSHAKE_OUT);
        messenger.registerOutgoingPluginChannel(plugin, UPDATE_OUT);
        messenger.registerIncomingPluginChannel(plugin, HANDSHAKE_IN, this);
        messenger.registerIncomingPluginChannel(plugin, REQUEST_IN, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    void disable() {
        for (BukkitTask task : pending.values()) {
            task.cancel();
        }
        pending.clear();
        clients.clear();
        lastSent.clear();
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, HANDSHAKE_IN, this);
        messenger.unregisterIncomingPluginChannel(plugin, REQUEST_IN, this);
        messenger.unregisterOutgoingPluginChannel(plugin, HANDSHAKE_OUT);
        messenger.unregisterOutgoingPluginChannel(plugin, UPDATE_OUT);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (HANDSHAKE_IN.equals(channel)) {
            onHandshake(player, message);
        } else if (REQUEST_IN.equals(channel) && clients.contains(player.getUniqueId())) {
            sendEnderChest(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && enderChest(event.getView())) {
            markChanged(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && enderChest(event.getView())) {
            markChanged(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && enderChest(event.getView())) {
            markChanged(player);
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        if (clients.contains(event.getPlayer().getUniqueId())) {
            sendEnderChest(event.getPlayer());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (clients.contains(event.getPlayer().getUniqueId())) {
            sendEnderChest(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    private void onHandshake(Player player, byte[] message) {
        if (message == null || message.length < 8) {
            plugin.getLogger().warning(player.getName() + " mandó un saludo de Shulker Box Tooltip incompleto.");
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(message);
        int major = buffer.getInt();
        int minor = buffer.getInt();
        if (major != PROTOCOL_MAJOR) {
            plugin.getLogger().warning(player.getName()
                    + " usa Shulker Box Tooltip " + major + "." + minor
                    + " y este servidor habla el protocolo " + PROTOCOL_MAJOR + "." + PROTOCOL_MINOR + ".");
            return;
        }
        if (!packets.available()) {
            plugin.getLogger().warning("No se pudo preparar el cofre de ender para Shulker Box Tooltip: "
                    + packets.failure());
            return;
        }
        byte[] reply = handshake();
        if (reply == null) {
            plugin.getLogger().warning("No se pudo responder el saludo de Shulker Box Tooltip: " + packets.failure());
            return;
        }
        clients.add(player.getUniqueId());
        send(player, HANDSHAKE_OUT, reply);
        sendEnderChest(player);
        plugin.getLogger().info(player.getName() + " conectó Shulker Box Tooltip " + major + "." + minor + ".");
    }

    private byte[] handshake() {
        if (handshake == null) {
            handshake = packets.handshake(PROTOCOL_MAJOR, PROTOCOL_MINOR);
        }
        return handshake;
    }

    private void sendEnderChest(Player player) {
        if (!player.isOnline() || !packets.available()) {
            return;
        }
        byte[] payload = packets.enderChest(player);
        if (payload == null) {
            plugin.getLogger().warning("No se pudo leer el cofre de ender de " + player.getName()
                    + " para Shulker Box Tooltip: " + packets.failure());
            return;
        }
        lastSent.put(player.getUniqueId(), System.currentTimeMillis());
        send(player, UPDATE_OUT, payload);
    }

    private void markChanged(Player player) {
        UUID id = player.getUniqueId();
        if (!clients.contains(id) || pending.containsKey(id)) {
            return;
        }
        long wait = lastSent.getOrDefault(id, 0L) + DEBOUNCE_MS - System.currentTimeMillis();
        long ticks = wait <= 0L ? 1L : Math.max(1L, (wait + 49L) / 50L);
        pending.put(id, plugin.getServer().getScheduler().runTaskLater(plugin, () -> flush(id), ticks));
    }

    private void flush(UUID id) {
        pending.remove(id);
        Player player = Bukkit.getPlayer(id);
        if (player != null && clients.contains(id)) {
            sendEnderChest(player);
        }
    }

    private void forget(UUID id) {
        clients.remove(id);
        lastSent.remove(id);
        BukkitTask task = pending.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    private void send(Player player, String channel, byte[] payload) {
        try {
            player.sendPluginMessage(plugin, channel, payload);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("No se pudo enviar " + channel + " a " + player.getName()
                    + ": " + exception.getMessage());
        }
    }

    private static boolean enderChest(InventoryView view) {
        return view.getTopInventory().getType() == InventoryType.ENDER_CHEST;
    }

    /**
     * Arma los mismos NBT que el mod, con las clases del servidor, porque el
     * formato del ítem no está en la API de Paper.
     */
    private static final class NbtPackets {
        private Method getHandle;
        private Method getEnderChest;
        private Method registryAccess;
        private Method createWithContext;
        private Method list;
        private Method storeAsSlots;
        private Method buildResult;
        private Method getListOrEmpty;
        private Method put;
        private Method putBoolean;
        private Method putString;
        private Method writeInt;
        private Method writeNbt;
        private Method readableBytes;
        private Method readBytes;
        private Method release;
        private Method buffer;
        private Constructor<?> compound;
        private Constructor<?> friendlyBuf;
        private Object discarding;
        private Object codec;
        private String failure = "aún no se preparó";
        private boolean ready;
        private boolean bound;

        boolean available() {
            if (!ready) {
                ready = true;
                try {
                    bind();
                    bound = true;
                    failure = null;
                } catch (ReflectiveOperationException exception) {
                    failure = exception.getMessage();
                }
            }
            return bound;
        }

        String failure() {
            return failure;
        }

        byte[] handshake(int major, int minor) {
            try {
                Object server = compound.newInstance();
                putBoolean.invoke(server, "clientIntegration", true);
                putString.invoke(server, "enderChestSyncType", "ACTIVE");
                Object root = compound.newInstance();
                put.invoke(root, "server", server);
                Object buf = friendlyBuf.newInstance(buffer.invoke(null));
                try {
                    writeInt.invoke(buf, major);
                    writeInt.invoke(buf, minor);
                    writeNbt.invoke(buf, root);
                    return copy(buf);
                } finally {
                    release.invoke(buf);
                }
            } catch (ReflectiveOperationException exception) {
                failure = exception.getMessage();
                return null;
            }
        }

        byte[] enderChest(Player player) {
            try {
                Object handle = getHandle.invoke(player);
                Object inventory = getEnderChest.invoke(handle);
                Object output = createWithContext.invoke(null, discarding, registryAccess.invoke(handle));
                storeAsSlots.invoke(inventory, list.invoke(output, "inv", codec));
                Object built = buildResult.invoke(output);
                Object wrapped = compound.newInstance();
                put.invoke(wrapped, "inv", getListOrEmpty.invoke(built, "inv"));
                Object buf = friendlyBuf.newInstance(buffer.invoke(null));
                try {
                    writeNbt.invoke(buf, wrapped);
                    return copy(buf);
                } finally {
                    release.invoke(buf);
                }
            } catch (ReflectiveOperationException exception) {
                failure = exception.getMessage();
                return null;
            }
        }

        private byte[] copy(Object buf) throws ReflectiveOperationException {
            byte[] bytes = new byte[(int) readableBytes.invoke(buf)];
            readBytes.invoke(buf, bytes);
            return bytes;
        }

        private void bind() throws ReflectiveOperationException {
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            getHandle = craftPlayer.getMethod("getHandle");
            Class<?> nmsPlayer = Class.forName("net.minecraft.world.entity.player.Player");
            getEnderChest = nmsPlayer.getMethod("getEnderChestInventory");
            registryAccess = Class.forName("net.minecraft.world.entity.Entity").getMethod("registryAccess");

            Class<?> reporter = Class.forName("net.minecraft.util.ProblemReporter");
            discarding = reporter.getField("DISCARDING").get(null);
            Class<?> outputType = Class.forName("net.minecraft.world.level.storage.TagValueOutput");
            createWithContext = outputType.getMethod("createWithContext", reporter,
                    Class.forName("net.minecraft.core.HolderLookup$Provider"));
            Class<?> codecType = Class.forName("com.mojang.serialization.Codec");
            list = outputType.getMethod("list", String.class, codecType);
            codec = Class.forName("net.minecraft.world.ItemStackWithSlot").getField("CODEC").get(null);
            Class<?> typedList = Class.forName("net.minecraft.world.level.storage.ValueOutput$TypedOutputList");
            storeAsSlots = Class.forName("net.minecraft.world.inventory.PlayerEnderChestContainer")
                    .getMethod("storeAsSlots", typedList);
            buildResult = outputType.getMethod("buildResult");

            Class<?> compoundTag = Class.forName("net.minecraft.nbt.CompoundTag");
            compound = compoundTag.getConstructor();
            getListOrEmpty = compoundTag.getMethod("getListOrEmpty", String.class);
            put = compoundTag.getMethod("put", String.class, Class.forName("net.minecraft.nbt.Tag"));
            putBoolean = compoundTag.getMethod("putBoolean", String.class, boolean.class);
            putString = compoundTag.getMethod("putString", String.class, String.class);

            Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf");
            Class<?> friendly = Class.forName("net.minecraft.network.FriendlyByteBuf");
            friendlyBuf = friendly.getConstructor(byteBuf);
            writeInt = friendly.getMethod("writeInt", int.class);
            writeNbt = friendly.getMethod("writeNbt", Class.forName("net.minecraft.nbt.Tag"));
            buffer = Class.forName("io.netty.buffer.Unpooled").getMethod("buffer");
            readableBytes = byteBuf.getMethod("readableBytes");
            readBytes = byteBuf.getMethod("readBytes", byte[].class);
            release = byteBuf.getMethod("release");
        }
    }
}
