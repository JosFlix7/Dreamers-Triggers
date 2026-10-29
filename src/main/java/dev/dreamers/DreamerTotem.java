package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DeathProtection;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

final class DreamerTotem {
    enum Kind {
        PLAIN,
        DREAMER,
        NUTRI,
        UNDEAD
    }

    private static final Key DREAMER_MODEL = Key.key("dreamers", "dreamers_totem");
    private static final Key NUTRI_MODEL = Key.key("dreamers", "nutritotem");
    private static final Key UNDEAD_MODEL = Key.key("dreamers", "totem_of_undeads");

    private static NamespacedKey dreamerMark;
    private static NamespacedKey typeMark;
    private static NamespacedKey recipe;
    private static NamespacedKey nutriRecipe;

    private DreamerTotem() {}

    static void enable(JavaPlugin plugin) {
        dreamerMark = new NamespacedKey(plugin, "dreamer_totem");
        typeMark = new NamespacedKey(plugin, "totem_type");
        recipe = new NamespacedKey(plugin, "dreamer_totem");
        if (Bukkit.getRecipe(recipe) == null) {
            ShapedRecipe shaped = new ShapedRecipe(recipe, dreamer());
            shaped.shape("DAD", "TET", "DAD");
            shaped.setIngredient('D', Material.DIAMOND);
            shaped.setIngredient('A', Material.AMETHYST_SHARD);
            shaped.setIngredient('E', Material.ENDER_EYE);
            shaped.setIngredient('T', plainTotem());
            Bukkit.addRecipe(shaped);
        }
        nutriRecipe = new NamespacedKey(plugin, "nutritotem");
        if (Bukkit.getRecipe(nutriRecipe) == null) {
            ShapedRecipe shaped = new ShapedRecipe(nutriRecipe, nutri());
            shaped.shape("LPL", "RTR", "LPR");
            shaped.setIngredient('L', Material.LAPIS_LAZULI);
            shaped.setIngredient('P', Material.GUNPOWDER);
            shaped.setIngredient('R', Material.REDSTONE);
            shaped.setIngredient('T', plainTotem());
            Bukkit.addRecipe(shaped);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            discover(player);
            refreshCarried(player);
        }
    }

    static void discover(Player player) {
        if (recipe != null) {
            player.discoverRecipe(recipe);
        }
        if (nutriRecipe != null) {
            player.discoverRecipe(nutriRecipe);
        }
    }

    static ItemStack dreamer() {
        ItemStack item = base(
                "Tótem Dreamer",
                ItemRarity.EPIC.color(),
                List.of(
                        Component.text("Popea en tu inventario sin necesidad de tenerlo en la segunda mano.", NamedTextColor.WHITE)
                                .decoration(TextDecoration.ITALIC, false),
                        Component.text("ADVERTENCIA: Se prioriza el tótem que tengas en tu mano principal o secundaria antes que este tótem", NamedTextColor.YELLOW)
                                .decoration(TextDecoration.ITALIC, false)
                ),
                DREAMER_MODEL
        );
        item.setData(DataComponentTypes.RARITY, ItemRarity.EPIC);
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        item.editMeta(meta -> meta.getPersistentDataContainer().set(dreamerMark, PersistentDataType.BOOLEAN, true));
        return item;
    }

    static ItemStack nutri() {
        ItemStack item = base(
                "Nutritótem",
                NamedTextColor.GREEN,
                List.of(line("Otorga 2 segundos de resistencia infinita para poder escapar de la situación peligrosa.")),
                NUTRI_MODEL
        );
        item.setData(DataComponentTypes.DEATH_PROTECTION, DeathProtection.deathProtection()
                .addEffect(ConsumeEffect.clearAllStatusEffects())
                .addEffect(ConsumeEffect.applyStatusEffects(List.of(
                        new PotionEffect(PotionEffectType.REGENERATION, 900, 1),
                        new PotionEffect(PotionEffectType.ABSORPTION, 100, 1),
                        new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0),
                        new PotionEffect(PotionEffectType.RESISTANCE, 40, 4)
                ), 1f))
                .build());
        mark(item, "nutri");
        return item;
    }

    static ItemStack undead() {
        ItemStack item = base(
                "Tótem de los no-muertos",
                NamedTextColor.DARK_PURPLE,
                List.of(line("Tótem que usan los no muertos para vengarse de tí, usarlo a tu favor te traerá consecuencias...")),
                UNDEAD_MODEL
        );
        mark(item, "undead");
        return item;
    }

    static void applyStandardEffects(LivingEntity entity) {
        entity.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        entity.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        entity.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
    }

    static void applyUndeadPlayerEffects(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 900, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 800, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 300, 0));
        player.setFoodLevel(20);
        player.setSaturation(20f);
    }

    static Kind kind(ItemStack item) {
        if (!isTotem(item)) {
            return Kind.PLAIN;
        }
        if (dreamerMark != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (meta.getPersistentDataContainer().has(dreamerMark, PersistentDataType.BOOLEAN)) {
                    return Kind.DREAMER;
                }
                String type = meta.getPersistentDataContainer().get(typeMark, PersistentDataType.STRING);
                if ("nutri".equals(type)) {
                    return Kind.NUTRI;
                }
                if ("undead".equals(type)) {
                    return Kind.UNDEAD;
                }
            }
        }
        Key model = item.getData(DataComponentTypes.ITEM_MODEL);
        if (NUTRI_MODEL.equals(model)) {
            return Kind.NUTRI;
        }
        if (UNDEAD_MODEL.equals(model)) {
            return Kind.UNDEAD;
        }
        if (DREAMER_MODEL.equals(model)) {
            return Kind.DREAMER;
        }
        return Kind.PLAIN;
    }

    static boolean isDreamer(ItemStack item) {
        return kind(item) == Kind.DREAMER;
    }

    static boolean isTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING && !item.isEmpty();
    }

    static void refreshCarried(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (isDreamer(item) && item.getData(DataComponentTypes.ITEM_MODEL) == null) {
                item.setData(DataComponentTypes.ITEM_MODEL, DREAMER_MODEL);
                player.getInventory().setItem(slot, item);
            }
        }
    }

    private static ItemStack base(String name, TextColor color, List<Component> lore, Key model) {
        ItemStack item = ItemStack.of(Material.TOTEM_OF_UNDYING);
        item.setData(DataComponentTypes.CUSTOM_NAME, Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        item.setData(DataComponentTypes.ITEM_MODEL, model);
        return item;
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
    }

    private static RecipeChoice plainTotem() {
        return RecipeChoice.predicateChoice(
                item -> isTotem(item) && kind(item) == Kind.PLAIN,
                ItemStack.of(Material.TOTEM_OF_UNDYING)
        );
    }

    private static void mark(ItemStack item, String type) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(typeMark, PersistentDataType.STRING, type));
    }
}
