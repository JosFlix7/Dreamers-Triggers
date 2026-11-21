package dev.dreamers;

import org.bukkit.Bukkit;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Optional;
import java.util.UUID;

/**
 * Lee y escribe el inventario guardado de un jugador desconectado.
 * En 26.3 el archivo vive en players/data. Los huecos 0 a 35 están en Inventory
 * y la armadura, el cuerpo, la montura y la mano secundaria en equipment.
 */
final class OfflineItems {
    static final EquipmentSlot[] WORN = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
            EquipmentSlot.OFF_HAND,
            EquipmentSlot.BODY,
            EquipmentSlot.SADDLE
    };

    private final JavaPlugin plugin;
    private boolean ready;
    private Throwable failure;
    private Object storage;
    private Method load;
    private Method playerDir;
    private Object codec;
    private Object ops;
    private Method parse;
    private Method encode;
    private Method orThrow;
    private Method asBukkit;
    private Method asNms;
    private Method isEmpty;
    private Method copy;
    private Method listOrEmpty;
    private Method compoundOrEmpty;
    private Method contains;
    private Method getTag;
    private Method put;
    private Method putByte;
    private Method remove;
    private Method isTagEmpty;
    private Method listSize;
    private Method compoundAt;
    private Method listAdd;
    private Method writeCompressed;
    private java.lang.reflect.Constructor<?> listNew;
    private java.lang.reflect.Constructor<?> nameNew;

    OfflineItems(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    static final class Snapshot {
        final ItemStack[] storage = new ItemStack[36];
        final ItemStack[] ender = new ItemStack[27];
        final EnumMap<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
    }

    Snapshot read(UUID id, String name) {
        if (!bind() || id == null || Bukkit.getPlayer(id) != null) {
            return null;
        }
        try {
            Object tag = fresh(id, name);
            if (tag == null) {
                return null;
            }
            Snapshot snapshot = new Snapshot();
            readStorage(tag, snapshot);
            readEquipment(tag, snapshot);
            readEnder(tag, snapshot);
            return snapshot;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("No se pudo leer el inventario de " + who(name) + ": " + exception.getMessage());
            return null;
        }
    }

    boolean write(UUID id, String name, Snapshot snapshot) {
        if (!bind() || id == null || snapshot == null || Bukkit.getPlayer(id) != null) {
            return false;
        }
        Path tmp = null;
        try {
            Object tag = fresh(id, name);
            File file = file(id);
            if (tag == null || file == null) {
                return false;
            }
            if (!apply(tag, snapshot)) {
                return false;
            }
            Path path = file.toPath();
            tmp = path.resolveSibling(id + ".dat.tmp");
            writeCompressed.invoke(null, tag, tmp);
            replace(path, tmp, path.resolveSibling(id + ".dat_old"));
            return true;
        } catch (RuntimeException | ReflectiveOperationException | IOException exception) {
            plugin.getLogger().warning("No se pudo guardar el inventario de " + who(name) + ": " + message(exception));
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // El temporal se queda si el disco no lo suelta.
                }
            }
            return false;
        }
    }

    private void readStorage(Object tag, Snapshot snapshot) {
        Object list = call(listOrEmpty, tag, "Inventory");
        int size = (int) call(listSize, list);
        for (int index = 0; index < size; index++) {
            Object compound = call(compoundAt, list, index);
            Integer slot = slot(compound);
            if (slot == null) {
                continue;
            }
            ItemStack item = decode(compound, true);
            if (item == null) {
                continue;
            }
            if (slot >= 0 && slot < snapshot.storage.length && snapshot.storage[slot] == null) {
                snapshot.storage[slot] = item;
            } else {
                EquipmentSlot worn = legacy(slot);
                if (worn != null && snapshot.equipment.get(worn) == null) {
                    snapshot.equipment.put(worn, item);
                }
            }
        }
    }

    private void readEquipment(Object tag, Snapshot snapshot) {
        Object equipment = call(compoundOrEmpty, tag, "equipment");
        for (EquipmentSlot slot : WORN) {
            String key = key(slot);
            if (!Boolean.TRUE.equals(call(contains, equipment, key))) {
                continue;
            }
            ItemStack item = decode(call(compoundOrEmpty, equipment, key), false);
            if (item != null) {
                snapshot.equipment.put(slot, item);
            }
        }
    }

    private void readEnder(Object tag, Snapshot snapshot) {
        Object list = call(listOrEmpty, tag, "EnderItems");
        int size = (int) call(listSize, list);
        for (int index = 0; index < size; index++) {
            Object compound = call(compoundAt, list, index);
            Integer slot = slot(compound);
            if (slot == null || slot < 0 || slot >= snapshot.ender.length) {
                continue;
            }
            ItemStack item = decode(compound, true);
            if (item != null && snapshot.ender[slot] == null) {
                snapshot.ender[slot] = item;
            }
        }
    }

    private boolean apply(Object tag, Snapshot snapshot) {
        Object storage = kept(call(listOrEmpty, tag, "Inventory"), true);
        Object ender = kept(call(listOrEmpty, tag, "EnderItems"), false);
        if (storage == null || ender == null) {
            return false;
        }
        for (int slot = 0; slot < snapshot.storage.length; slot++) {
            if (!append(storage, snapshot.storage[slot], slot)) {
                return false;
            }
        }
        for (int slot = 0; slot < snapshot.ender.length; slot++) {
            if (!append(ender, snapshot.ender[slot], slot)) {
                return false;
            }
        }
        Object equipment = call(copy, call(compoundOrEmpty, tag, "equipment"));
        for (EquipmentSlot slot : WORN) {
            ItemStack item = snapshot.equipment.get(slot);
            String key = key(slot);
            if (item == null || item.isEmpty()) {
                call(remove, equipment, key);
                continue;
            }
            Object encoded = encode(item);
            if (encoded == null) {
                return false;
            }
            call(put, equipment, key, encoded);
        }
        call(put, tag, "Inventory", storage);
        call(put, tag, "EnderItems", ender);
        if (Boolean.TRUE.equals(call(isTagEmpty, equipment))) {
            call(remove, tag, "equipment");
        } else {
            call(put, tag, "equipment", equipment);
        }
        return true;
    }

    private Object kept(Object list, boolean inventory) {
        try {
            Object kept = listNew.newInstance();
            int size = (int) call(listSize, list);
            for (int index = 0; index < size; index++) {
                Object compound = call(compoundAt, list, index);
                Integer slot = slot(compound);
                if (slot == null) {
                    call(listAdd, kept, call(copy, compound));
                    continue;
                }
                boolean managed = inventory ? slot >= 0 && slot < 36 || legacy(slot) != null : slot >= 0 && slot < 27;
                if (!managed) {
                    call(listAdd, kept, call(copy, compound));
                }
            }
            return kept;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean append(Object list, ItemStack item, int slot) {
        if (item == null || item.isEmpty()) {
            return true;
        }
        Object encoded = encode(item);
        if (encoded == null) {
            return false;
        }
        call(putByte, encoded, "Slot", (byte) slot);
        call(listAdd, list, encoded);
        return true;
    }

    private ItemStack decode(Object compound, boolean slotted) {
        try {
            Object data = slotted ? call(copy, compound) : compound;
            if (slotted) {
                call(remove, data, "Slot");
            }
            Object stack = orThrow.invoke(parse.invoke(codec, ops, data));
            if (Boolean.TRUE.equals(isEmpty.invoke(stack))) {
                return null;
            }
            ItemStack item = (ItemStack) asBukkit.invoke(null, stack);
            return item == null || item.isEmpty() ? null : item;
        } catch (RuntimeException | ReflectiveOperationException exception) {
            plugin.getLogger().warning("No se pudo leer un objeto guardado: " + message(exception));
            return null;
        }
    }

    private Object encode(ItemStack item) {
        try {
            Object stack = asNms.invoke(null, item.clone());
            if (Boolean.TRUE.equals(isEmpty.invoke(stack))) {
                return null;
            }
            return orThrow.invoke(encode.invoke(codec, ops, stack));
        } catch (RuntimeException | ReflectiveOperationException exception) {
            plugin.getLogger().warning("No se pudo guardar un objeto: " + message(exception));
            return null;
        }
    }

    private Object fresh(UUID id, String name) {
        try {
            Object profile = nameNew.newInstance(id, name == null ? "" : name);
            Object loaded = load.invoke(storage, profile);
            if (!(loaded instanceof Optional<?> optional)) {
                return null;
            }
            Object tag = optional.orElse(null);
            return tag == null ? null : call(copy, tag);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private File file(UUID id) {
        File dir = (File) call(playerDir, storage);
        if (dir == null) {
            return null;
        }
        File file = new File(dir, id + ".dat");
        return file.isFile() ? file : null;
    }

    private boolean bind() {
        if (ready) {
            return failure == null;
        }
        ready = true;
        try {
            Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
            Object server = serverClass.getMethod("getServer").invoke(null);
            Field stored = serverClass.getDeclaredField("playerDataStorage");
            stored.setAccessible(true);
            storage = stored.get(server);
            Class<?> names = Class.forName("net.minecraft.server.players.NameAndId");
            nameNew = names.getConstructor(UUID.class, String.class);
            load = storage.getClass().getMethod("load", names);
            playerDir = storage.getClass().getMethod("getPlayerDir");
            Class<?> tags = Class.forName("net.minecraft.nbt.Tag");
            Class<?> compounds = Class.forName("net.minecraft.nbt.CompoundTag");
            Class<?> lists = Class.forName("net.minecraft.nbt.ListTag");
            listNew = lists.getConstructor();
            copy = compounds.getMethod("copy");
            listOrEmpty = compounds.getMethod("getListOrEmpty", String.class);
            compoundOrEmpty = compounds.getMethod("getCompoundOrEmpty", String.class);
            contains = compounds.getMethod("contains", String.class);
            getTag = compounds.getMethod("get", String.class);
            put = compounds.getMethod("put", String.class, tags);
            putByte = compounds.getMethod("putByte", String.class, byte.class);
            remove = compounds.getMethod("remove", String.class);
            isTagEmpty = compounds.getMethod("isEmpty");
            listSize = lists.getMethod("size");
            compoundAt = lists.getMethod("getCompoundOrEmpty", int.class);
            listAdd = lists.getMethod("add", Object.class);
            Class<?> items = Class.forName("net.minecraft.world.item.ItemStack");
            codec = items.getField("CODEC").get(null);
            isEmpty = items.getMethod("isEmpty");
            Class<?> opsClass = Class.forName("com.mojang.serialization.DynamicOps");
            Class<?> codecClass = Class.forName("com.mojang.serialization.Codec");
            parse = codecClass.getMethod("parse", opsClass, Object.class);
            encode = codecClass.getMethod("encodeStart", opsClass, Object.class);
            orThrow = Class.forName("com.mojang.serialization.DataResult").getMethod("getOrThrow");
            Object nbtOps = Class.forName("net.minecraft.nbt.NbtOps").getField("INSTANCE").get(null);
            Object registries = serverClass.getMethod("registryAccess").invoke(server);
            Class<?> provider = Class.forName("net.minecraft.core.HolderLookup$Provider");
            ops = Class.forName("net.minecraft.resources.RegistryOps").getMethod("create", opsClass, provider).invoke(null, nbtOps, registries);
            Class<?> craft = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            asBukkit = craft.getMethod("asBukkitCopy", Class.forName("net.minecraft.world.item.ItemInstance"));
            asNms = craft.getMethod("asNMSCopy", ItemStack.class);
            writeCompressed = Class.forName("net.minecraft.nbt.NbtIo").getMethod("writeCompressed", compounds, Path.class);
            return true;
        } catch (ReflectiveOperationException exception) {
            failure = exception;
            plugin.getLogger().warning("No se puede leer el inventario de jugadores desconectados: " + exception.getMessage());
            return false;
        }
    }

    private static void replace(Path path, Path tmp, Path backup) throws IOException {
        boolean moved = false;
        try {
            if (Files.exists(path)) {
                move(path, backup);
                moved = true;
            }
            move(tmp, path);
        } catch (IOException exception) {
            if (moved && Files.notExists(path) && Files.exists(backup)) {
                move(backup, path);
            }
            throw exception;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    private Integer slot(Object compound) {
        if (!Boolean.TRUE.equals(call(contains, compound, "Slot"))) {
            return null;
        }
        try {
            return number(call(getTag, compound, "Slot"));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static int number(Object tag) {
        String text = String.valueOf(tag).trim();
        int end = text.length();
        while (end > 0 && Character.isLetter(text.charAt(end - 1))) {
            end--;
        }
        return Integer.parseInt(text.substring(0, end));
    }

    private static EquipmentSlot legacy(int slot) {
        return switch (slot) {
            case 100 -> EquipmentSlot.FEET;
            case 101 -> EquipmentSlot.LEGS;
            case 102 -> EquipmentSlot.CHEST;
            case 103 -> EquipmentSlot.HEAD;
            case -106 -> EquipmentSlot.OFF_HAND;
            default -> null;
        };
    }

    static String key(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "head";
            case CHEST -> "chest";
            case LEGS -> "legs";
            case FEET -> "feet";
            case OFF_HAND -> "offhand";
            case BODY -> "body";
            case SADDLE -> "saddle";
            default -> null;
        };
    }

    private static String who(String name) {
        return name == null || name.isBlank() ? "un jugador" : name;
    }

    private static String message(Throwable exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        String text = cause.getMessage();
        return text == null || text.isBlank() ? cause.getClass().getSimpleName() : text;
    }
}
