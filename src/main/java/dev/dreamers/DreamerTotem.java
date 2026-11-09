package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DeathProtection;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Map;

final class DreamerTotem {
    enum Kind {
        PLAIN,
        DREAMER,
        NUTRI,
        FERREO,
        YUNQUE,
        UNDEAD
    }

    private static final Key DREAMER_MODEL = Key.key("dreamers", "dreamers_totem");
    private static final Key NUTRI_MODEL = Key.key("dreamers", "nutritotem");
    private static final Key FERREO_MODEL = Key.key("dreamers", "totem_ferreo");
    private static final Key YUNQUE_MODEL = Key.key("dreamers", "totem_yunque");
    private static final Key UNDEAD_MODEL = Key.key("dreamers", "totem_of_undeads");
    private static final NamespacedKey FERREO_HEALTH = new NamespacedKey("dreamers", "totem_ferreo_health");
    private static final NamespacedKey DREAMER_HEALTH = new NamespacedKey("dreamers", "totem_dreamer_health");
    private static final Component NUTRI_NAME = NameColor.named("Nutritótem", ItemRarity.UNCOMMON.color());
    private static final Component FERREO_NAME = NameColor.named("Tótem Férreo", ItemRarity.RARE.color());
    private static final Component YUNQUE_NAME = NameColor.named("Tótem Yunque", ItemRarity.RARE.color());
    private static final Component UNDEAD_NAME = NameColor.named("Tótem de los no-muertos", ItemRarity.RARE.color());
    private static final Component DREAMER_NAME = NameColor.named("Tótem Dreamer", ItemRarity.EPIC.color());
    private static final List<Component> NUTRI_LORE = List.of(
            line("Otorga 2 segundos de resistencia infinita"),
            line("para poder escapar de una situación peligrosa.")
    );
    private static final List<Component> FERREO_LORE = List.of(
            line("Otorga los mismos efectos que un"),
            line("Nutritótem al popear."),
            line("Otorga dos corazones extras cuando"),
            line("lo tienes en la mano secundaria")
    );
    private static final List<Component> YUNQUE_LORE = List.of(
            line("Otorga los mismos efectos que un"),
            line("Nutritótem al popear y además"),
            line("repara tu set completo de armadura.")
    );
    private static final List<Component> UNDEAD_LORE = List.of(
            line("Tótem que usan los no muertos para"),
            line("vengarse de tí, usarlo a tu favor te traerá"),
            line("consecuencias...")
    );
    private static final List<Component> DREAMER_LORE = List.of(
            line("Popea en tu inventario sin necesidad de"),
            line("tenerlo en tu mano principal o secundaria."),
            line("Otorga 4 segundos de resistencia infinita"),
            line("al popear y cuatro corazones extras cuando"),
            line("lo tienes en la mano secundaria."),
            line("ADVERTENCIA: El juego prioriza usar el", NamedTextColor.YELLOW),
            line("tótem que tengas en tu mano principal o", NamedTextColor.YELLOW),
            line("secundaria antes que este tótem", NamedTextColor.YELLOW)
    );

    private static NamespacedKey dreamerMark;
    private static NamespacedKey typeMark;
    private static NamespacedKey recipe;
    private static NamespacedKey nutriRecipe;
    private static NamespacedKey ferreoRecipe;
    private static NamespacedKey yunqueRecipe;

    private DreamerTotem() {}

    static void enable(JavaPlugin plugin) {
        dreamerMark = new NamespacedKey(plugin, "dreamer_totem");
        typeMark = new NamespacedKey(plugin, "totem_type");
        recipe = new NamespacedKey(plugin, "dreamer_totem");
        Bukkit.removeRecipe(recipe);
        ShapedRecipe dreamerRecipe = new ShapedRecipe(recipe, dreamer());
        dreamerRecipe.shape("DAD", "TET", "DAD");
        dreamerRecipe.setIngredient('D', Material.DIAMOND);
        dreamerRecipe.setIngredient('A', Material.AMETHYST_SHARD);
        dreamerRecipe.setIngredient('E', Material.ENDER_EYE);
        dreamerRecipe.setIngredient('T', ferreoTotem());
        Bukkit.addRecipe(dreamerRecipe);
        nutriRecipe = new NamespacedKey(plugin, "nutritotem");
        Bukkit.removeRecipe(nutriRecipe);
        ShapedRecipe shaped = new ShapedRecipe(nutriRecipe, nutri());
        shaped.shape("DED", "ETE", "DED");
        shaped.setIngredient('D', Material.DIAMOND);
        shaped.setIngredient('E', Material.EMERALD);
        shaped.setIngredient('T', plainTotem());
        Bukkit.addRecipe(shaped);
        ferreoRecipe = new NamespacedKey(plugin, "totem_ferreo");
        Bukkit.removeRecipe(ferreoRecipe);
        ShapedRecipe iron = new ShapedRecipe(ferreoRecipe, ferreo());
        iron.shape("III", "INI", "III");
        iron.setIngredient('I', Material.IRON_BLOCK);
        iron.setIngredient('N', nutriTotem());
        Bukkit.addRecipe(iron);
        yunqueRecipe = new NamespacedKey(plugin, "totem_yunque");
        Bukkit.removeRecipe(yunqueRecipe);
        ShapedRecipe anvil = new ShapedRecipe(yunqueRecipe, yunque());
        anvil.shape("DBD", "DND", "DAD");
        anvil.setIngredient('D', Material.DIAMOND);
        anvil.setIngredient('B', mendingBook());
        anvil.setIngredient('N', nutriTotem());
        anvil.setIngredient('A', Material.ANVIL);
        Bukkit.addRecipe(anvil);
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
        if (ferreoRecipe != null) {
            player.discoverRecipe(ferreoRecipe);
        }
        if (yunqueRecipe != null) {
            player.discoverRecipe(yunqueRecipe);
        }
    }

    static ItemStack dreamer() {
        ItemStack item = base(DREAMER_NAME, ItemRarity.EPIC, DREAMER_LORE, DREAMER_MODEL);
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, dreamerAttributes());
        item.editMeta(meta -> meta.getPersistentDataContainer().set(dreamerMark, PersistentDataType.BOOLEAN, true));
        return item;
    }

    static ItemStack nutri() {
        ItemStack item = base(NUTRI_NAME, ItemRarity.UNCOMMON, NUTRI_LORE, NUTRI_MODEL);
        nutriProtection(item);
        mark(item, "nutri");
        return item;
    }

    static ItemStack ferreo() {
        ItemStack item = base(FERREO_NAME, ItemRarity.RARE, FERREO_LORE, FERREO_MODEL);
        nutriProtection(item);
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes()
                .addModifier(
                        Attribute.MAX_HEALTH,
                        new AttributeModifier(FERREO_HEALTH, 4, AttributeModifier.Operation.ADD_NUMBER),
                        EquipmentSlotGroup.OFFHAND,
                        AttributeModifierDisplay.hidden()
                )
                .build());
        mark(item, "ferreo");
        return item;
    }

    static ItemStack yunque() {
        ItemStack item = base(YUNQUE_NAME, ItemRarity.RARE, YUNQUE_LORE, YUNQUE_MODEL);
        nutriProtection(item);
        mark(item, "yunque");
        return item;
    }

    static ItemStack undead() {
        ItemStack item = base(UNDEAD_NAME, ItemRarity.RARE, UNDEAD_LORE, UNDEAD_MODEL);
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
            var data = item.getPersistentDataContainer();
            if (data.has(dreamerMark, PersistentDataType.BOOLEAN)) {
                return Kind.DREAMER;
            }
            String type = data.get(typeMark, PersistentDataType.STRING);
            if (type != null) {
                switch (type) {
                    case "nutri" -> {
                        return Kind.NUTRI;
                    }
                    case "ferreo" -> {
                        return Kind.FERREO;
                    }
                    case "yunque" -> {
                        return Kind.YUNQUE;
                    }
                    case "undead" -> {
                        return Kind.UNDEAD;
                    }
                    default -> {
                    }
                }
            }
        }
        Key model = item.getData(DataComponentTypes.ITEM_MODEL);
        if (NUTRI_MODEL.equals(model)) {
            return Kind.NUTRI;
        }
        if (FERREO_MODEL.equals(model)) {
            return Kind.FERREO;
        }
        if (YUNQUE_MODEL.equals(model)) {
            return Kind.YUNQUE;
        }
        if (UNDEAD_MODEL.equals(model)) {
            return Kind.UNDEAD;
        }
        if (DREAMER_MODEL.equals(model)) {
            return Kind.DREAMER;
        }
        return Kind.PLAIN;
    }

    static TextColor chatColor(Kind kind) {
        ItemRarity rarity = rarity(kind);
        return rarity == null ? ItemRarity.UNCOMMON.color() : rarity.color();
    }

    static boolean isDreamer(ItemStack item) {
        return kind(item) == Kind.DREAMER;
    }

    static boolean tint(ItemStack item) {
        return tint(item, kind(item));
    }

    private static boolean tint(ItemStack item, Kind kind) {
        ItemRarity rarity = rarity(kind);
        if (rarity == null) {
            return false;
        }
        boolean changed = false;
        if (item.getData(DataComponentTypes.RARITY) != rarity) {
            item.setData(DataComponentTypes.RARITY, rarity);
            changed = true;
        }
        Component current = item.getData(DataComponentTypes.CUSTOM_NAME);
        Component next = current == null || isDefaultName(current, kind)
                ? defaultName(kind)
                : NameColor.paint(current, rarity.color());
        if (!next.equals(current)) {
            item.setData(DataComponentTypes.CUSTOM_NAME, next);
            changed = true;
        }
        List<Component> expected = lore(kind);
        if (!loreMatches(item, expected)) {
            item.setData(DataComponentTypes.LORE, ItemLore.lore(expected));
            changed = true;
        }
        return changed;
    }

    private static ItemRarity rarity(Kind kind) {
        return switch (kind) {
            case NUTRI -> ItemRarity.UNCOMMON;
            case FERREO, YUNQUE, UNDEAD -> ItemRarity.RARE;
            case DREAMER -> ItemRarity.EPIC;
            default -> null;
        };
    }

    private static Component defaultName(Kind kind) {
        return switch (kind) {
            case NUTRI -> NUTRI_NAME;
            case FERREO -> FERREO_NAME;
            case YUNQUE -> YUNQUE_NAME;
            case UNDEAD -> UNDEAD_NAME;
            case DREAMER -> DREAMER_NAME;
            default -> NUTRI_NAME;
        };
    }

    private static String defaultText(Kind kind) {
        return switch (kind) {
            case NUTRI -> "Nutritótem";
            case FERREO -> "Tótem Férreo";
            case YUNQUE -> "Tótem Yunque";
            case UNDEAD -> "Tótem de los no-muertos";
            case DREAMER -> "Tótem Dreamer";
            default -> "Tótem";
        };
    }

    private static boolean isDefaultName(Component name, Kind kind) {
        return PlainTextComponentSerializer.plainText().serialize(name).equals(defaultText(kind));
    }

    private static boolean loreMatches(ItemStack item, List<Component> expected) {
        ItemLore lore = item.getData(DataComponentTypes.LORE);
        if (lore == null || lore.lines().size() != expected.size()) {
            return false;
        }
        List<Component> lines = lore.lines();
        for (int index = 0; index < expected.size(); index++) {
            if (!expected.get(index).equals(lines.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static List<Component> lore(Kind kind) {
        return switch (kind) {
            case NUTRI -> NUTRI_LORE;
            case FERREO -> FERREO_LORE;
            case YUNQUE -> YUNQUE_LORE;
            case UNDEAD -> UNDEAD_LORE;
            case DREAMER -> DREAMER_LORE;
            default -> List.of();
        };
    }

    static boolean isTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING && !item.isEmpty();
    }

    static void refreshCarried(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            Kind found = kind(item);
            if (found != Kind.DREAMER) {
                continue;
            }
            item.setData(DataComponentTypes.ITEM_MODEL, DREAMER_MODEL);
            item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, dreamerAttributes());
            tint(item, found);
            player.getInventory().setItem(slot, item);
        }
    }

    private static ItemStack base(Component name, ItemRarity rarity, List<Component> lore, Key model) {
        ItemStack item = ItemStack.of(Material.TOTEM_OF_UNDYING);
        item.setData(DataComponentTypes.CUSTOM_NAME, name);
        item.setData(DataComponentTypes.RARITY, rarity);
        item.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        item.setData(DataComponentTypes.ITEM_MODEL, model);
        return item;
    }

    private static Component line(String text) {
        return line(text, NamedTextColor.WHITE);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static void nutriProtection(ItemStack item) {
        item.setData(DataComponentTypes.DEATH_PROTECTION, DeathProtection.deathProtection()
                .addEffect(ConsumeEffect.clearAllStatusEffects())
                .addEffect(ConsumeEffect.applyStatusEffects(List.of(
                        new PotionEffect(PotionEffectType.REGENERATION, 900, 1),
                        new PotionEffect(PotionEffectType.ABSORPTION, 100, 1),
                        new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0),
                        new PotionEffect(PotionEffectType.RESISTANCE, 40, 4)
                ), 1f))
                .build());
    }

    private static RecipeChoice plainTotem() {
        return RecipeChoice.predicateChoice(
                item -> isTotem(item) && kind(item) == Kind.PLAIN,
                ItemStack.of(Material.TOTEM_OF_UNDYING)
        );
    }

    private static RecipeChoice nutriTotem() {
        return RecipeChoice.predicateChoice(
                item -> isTotem(item) && kind(item) == Kind.NUTRI,
                nutri()
        );
    }

    private static RecipeChoice ferreoTotem() {
        return RecipeChoice.predicateChoice(
                item -> isTotem(item) && kind(item) == Kind.FERREO,
                ferreo()
        );
    }

    private static RecipeChoice mendingBook() {
        ItemStack book = ItemStack.of(Material.ENCHANTED_BOOK);
        book.setData(
                DataComponentTypes.STORED_ENCHANTMENTS,
                ItemEnchantments.itemEnchantments(Map.of(Enchantment.MENDING, 1))
        );
        return RecipeChoice.predicateChoice(DreamerTotem::onlyMending, book);
    }

    private static boolean onlyMending(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK) {
            return false;
        }
        ItemEnchantments stored = item.getData(DataComponentTypes.STORED_ENCHANTMENTS);
        if (stored == null) {
            return false;
        }
        Map<Enchantment, Integer> enchants = stored.enchantments();
        if (enchants.size() != 1) {
            return false;
        }
        Map.Entry<Enchantment, Integer> only = enchants.entrySet().iterator().next();
        Integer level = only.getValue();
        return level != null && level > 0 && only.getKey().getKey().equals(Enchantment.MENDING.getKey());
    }

    private static ItemAttributeModifiers dreamerAttributes() {
        return ItemAttributeModifiers.itemAttributes()
                .addModifier(
                        Attribute.MAX_HEALTH,
                        new AttributeModifier(DREAMER_HEALTH, 8, AttributeModifier.Operation.ADD_NUMBER),
                        EquipmentSlotGroup.OFFHAND,
                        AttributeModifierDisplay.hidden()
                )
                .build();
    }

    private static void mark(ItemStack item, String type) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(typeMark, PersistentDataType.STRING, type));
    }
}
