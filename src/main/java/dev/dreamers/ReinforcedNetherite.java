package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class ReinforcedNetherite {
    private static NamespacedKey mark;
    private static NamespacedKey recipe;

    private ReinforcedNetherite() {
    }

    static void enable(JavaPlugin plugin) {
        mark = new NamespacedKey(plugin, "reinforced_netherite");
        recipe = new NamespacedKey("dreamers", "reinforced_netherite");
        Bukkit.removeRecipe(recipe);
        ShapedRecipe shaped = new ShapedRecipe(recipe, reinforce(ItemStack.of(Material.NETHERITE_HELMET)));
        shaped.shape("DPD", "NAR", "DPD");
        shaped.setIngredient('D', Material.DIAMOND);
        shaped.setIngredient('P', Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        shaped.setIngredient('N', Material.NETHERITE_INGOT);
        shaped.setIngredient('R', Material.REINFORCED_DEEPSLATE);
        shaped.setIngredient('A', RecipeChoice.predicateChoice(
                ReinforcedNetherite::accepts,
                ItemStack.of(Material.NETHERITE_HELMET)
        ));
        Bukkit.addRecipe(shaped);
        plugin.getServer().getPluginManager().registerEvents(new CraftListener(), plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.discoverRecipe(recipe);
        }
    }

    private static boolean accepts(ItemStack stack) {
        return isPiece(stack) && !reinforced(stack);
    }

    private static boolean isPiece(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return switch (stack.getType()) {
            case NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS -> true;
            default -> false;
        };
    }

    private static boolean reinforced(ItemStack stack) {
        return mark != null && stack.getPersistentDataContainer().has(mark, PersistentDataType.BOOLEAN);
    }

    private static ItemStack pieceIn(ItemStack[] matrix) {
        if (matrix == null) {
            return null;
        }
        for (ItemStack item : matrix) {
            if (accepts(item)) {
                return item;
            }
        }
        return null;
    }

    private static ItemStack reinforce(ItemStack source) {
        ItemStack result = source.clone();
        result.setAmount(1);
        int max = currentMax(result);
        if (max > 0) {
            int doubled = max > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : max * 2;
            result.setData(DataComponentTypes.MAX_DAMAGE, doubled);
        }
        applyAttributes(result);
        Component current = result.getData(DataComponentTypes.CUSTOM_NAME);
        result.setData(
                DataComponentTypes.CUSTOM_NAME,
                current == null ? defaultName(result.getType()) : current.color(ItemRarity.EPIC.color())
        );
        result.setData(DataComponentTypes.RARITY, ItemRarity.EPIC);
        result.editMeta(meta -> meta.getPersistentDataContainer().set(mark, PersistentDataType.BOOLEAN, true));
        return result;
    }

    private static int currentMax(ItemStack stack) {
        Integer stored = stack.getData(DataComponentTypes.MAX_DAMAGE);
        if (stored != null && stored > 0) {
            return stored;
        }
        return stack.getType().getMaxDurability();
    }

    private static void applyAttributes(ItemStack result) {
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        ItemAttributeModifiers existing = result.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        NamespacedKey health = healthId(result.getType());
        boolean keptArmor = false;
        boolean already = false;
        if (existing != null && !existing.modifiers().isEmpty()) {
            for (ItemAttributeModifiers.Entry entry : existing.modifiers()) {
                builder.addModifier(entry.attribute(), entry.modifier(), entry.getGroup(), entry.display());
                if (Attribute.ARMOR.equals(entry.attribute())) {
                    keptArmor = true;
                }
                if (health.equals(entry.modifier().getKey())) {
                    already = true;
                }
            }
        } else {
            ItemType type = result.getType().asItemType();
            if (type != null) {
                EquipmentSlotGroup fallback = slot(result.getType());
                for (var entry : type.getDefaultAttributeModifiers().entries()) {
                    AttributeModifier modifier = entry.getValue();
                    EquipmentSlotGroup group = modifier.getSlotGroup() == null ? fallback : modifier.getSlotGroup();
                    builder.addModifier(entry.getKey(), modifier, group, AttributeModifierDisplay.reset());
                    if (Attribute.ARMOR.equals(entry.getKey())) {
                        keptArmor = true;
                    }
                }
            }
        }
        if (!keptArmor) {
            addVanillaArmor(builder, result.getType());
        }
        if (!already) {
            EquipmentSlotGroup group = slot(result.getType());
            builder.addModifier(
                    Attribute.MAX_HEALTH,
                    new AttributeModifier(health, 2, AttributeModifier.Operation.ADD_NUMBER, group)
            );
        }
        result.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build());
    }

    private static void addVanillaArmor(ItemAttributeModifiers.Builder builder, Material type) {
        EquipmentSlotGroup group = slot(type);
        int armor = switch (type) {
            case NETHERITE_CHESTPLATE -> 8;
            case NETHERITE_LEGGINGS -> 6;
            case NETHERITE_HELMET, NETHERITE_BOOTS -> 3;
            default -> 0;
        };
        if (armor == 0) {
            return;
        }
        String piece = switch (type) {
            case NETHERITE_HELMET -> "helmet";
            case NETHERITE_CHESTPLATE -> "chestplate";
            case NETHERITE_LEGGINGS -> "leggings";
            default -> "boots";
        };
        NamespacedKey id = NamespacedKey.minecraft("armor." + piece);
        AttributeModifier.Operation add = AttributeModifier.Operation.ADD_NUMBER;
        builder.addModifier(Attribute.ARMOR, new AttributeModifier(id, armor, add, group));
        builder.addModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(id, 3, add, group));
        builder.addModifier(Attribute.KNOCKBACK_RESISTANCE, new AttributeModifier(id, 0.1, add, group));
    }

    private static EquipmentSlotGroup slot(Material type) {
        return switch (type) {
            case NETHERITE_HELMET -> EquipmentSlotGroup.HEAD;
            case NETHERITE_CHESTPLATE -> EquipmentSlotGroup.CHEST;
            case NETHERITE_LEGGINGS -> EquipmentSlotGroup.LEGS;
            case NETHERITE_BOOTS -> EquipmentSlotGroup.FEET;
            default -> EquipmentSlotGroup.ARMOR;
        };
    }

    private static NamespacedKey healthId(Material type) {
        String piece = switch (type) {
            case NETHERITE_HELMET -> "head";
            case NETHERITE_CHESTPLATE -> "chest";
            case NETHERITE_LEGGINGS -> "legs";
            case NETHERITE_BOOTS -> "feet";
            default -> "armor";
        };
        return new NamespacedKey("dreamers", "reinforced_netherite_health_" + piece);
    }

    private static Component defaultName(Material type) {
        String text = switch (type) {
            case NETHERITE_HELMET -> "Casco de Netherita reforzada";
            case NETHERITE_CHESTPLATE -> "Pechera de Netherita reforzada";
            case NETHERITE_LEGGINGS -> "Pantalones de Netherita reforzada";
            case NETHERITE_BOOTS -> "Botas de Netherita reforzada";
            default -> "Netherita reforzada";
        };
        return Component.text(text, ItemRarity.EPIC.color()).decoration(TextDecoration.ITALIC, false);
    }

    private static boolean isRecipe(Recipe crafted) {
        return recipe != null && crafted instanceof ShapedRecipe shaped && recipe.equals(shaped.getKey());
    }

    private static final class CraftListener implements Listener {
        @EventHandler
        public void onPrepare(PrepareItemCraftEvent event) {
            if (!isRecipe(event.getRecipe())) {
                return;
            }
            ItemStack source = pieceIn(event.getInventory().getMatrix());
            event.getInventory().setResult(source == null ? null : reinforce(source));
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onCraft(CraftItemEvent event) {
            if (!isRecipe(event.getRecipe())) {
                return;
            }
            ItemStack source = pieceIn(event.getInventory().getMatrix());
            if (source == null) {
                event.setCancelled(true);
                return;
            }
            ItemStack result = reinforce(source);
            event.setCurrentItem(result);
            event.getInventory().setResult(result);
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            if (recipe != null) {
                event.getPlayer().discoverRecipe(recipe);
            }
        }
    }
}
