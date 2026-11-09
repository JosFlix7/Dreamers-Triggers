package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Enchantable;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.Tool;
import io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay;
import net.kyori.adventure.text.Component;
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

    static boolean isGear(Material type) {
        return switch (type) {
            case NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS,
                    NETHERITE_AXE, NETHERITE_HOE, NETHERITE_PICKAXE, NETHERITE_SHOVEL,
                    NETHERITE_SWORD, NETHERITE_SPEAR -> true;
            default -> false;
        };
    }

    private static boolean isPiece(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && isGear(stack.getType());
    }

    private static boolean armor(Material type) {
        return switch (type) {
            case NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS -> true;
            default -> false;
        };
    }

    private static boolean diggingTool(Material type) {
        return switch (type) {
            case NETHERITE_AXE, NETHERITE_HOE, NETHERITE_PICKAXE, NETHERITE_SHOVEL -> true;
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

    static ItemStack reinforced(Material type) {
        if (!isGear(type)) {
            return null;
        }
        return reinforce(ItemStack.of(type));
    }

    private static ItemStack reinforce(ItemStack source) {
        ItemStack result = source.clone();
        result.setAmount(1);
        int max = currentMax(result);
        if (max > 0) {
            int doubled = max > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : max * 2;
            result.setData(DataComponentTypes.MAX_DAMAGE, doubled);
        }
        if (armor(result.getType())) {
            applyAttributes(result);
        } else {
            applyTool(result);
        }
        result.setData(DataComponentTypes.ENCHANTABLE, Enchantable.enchantable(20));
        result.editMeta(meta -> meta.getPersistentDataContainer().set(mark, PersistentDataType.BOOLEAN, true));
        tint(result);
        return result;
    }

    static boolean tint(ItemStack stack) {
        if (!isPiece(stack) || !reinforced(stack)) {
            return false;
        }
        boolean changed = false;
        if (stack.getData(DataComponentTypes.RARITY) != ItemRarity.EPIC) {
            stack.setData(DataComponentTypes.RARITY, ItemRarity.EPIC);
            changed = true;
        }
        Component current = stack.getData(DataComponentTypes.CUSTOM_NAME);
        Component next = current == null
                ? defaultName(stack.getType())
                : NameColor.paint(current, ItemRarity.EPIC.color());
        if (!next.equals(current)) {
            stack.setData(DataComponentTypes.CUSTOM_NAME, next);
            changed = true;
        }
        return changed;
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
                AttributeModifier modifier = toughness(entry.attribute(), entry.modifier(), entry.getGroup());
                builder.addModifier(entry.attribute(), modifier, entry.getGroup(), entry.display());
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
                    modifier = toughness(entry.getKey(), modifier, group);
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
        builder.addModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(id, 4, add, group));
        builder.addModifier(Attribute.KNOCKBACK_RESISTANCE, new AttributeModifier(id, 0.1, add, group));
    }

    private static AttributeModifier toughness(Attribute attribute, AttributeModifier modifier, EquipmentSlotGroup group) {
        if (!Attribute.ARMOR_TOUGHNESS.equals(attribute) || modifier.getAmount() != 3.0) {
            return modifier;
        }
        EquipmentSlotGroup slot = modifier.getSlotGroup() == null ? group : modifier.getSlotGroup();
        return new AttributeModifier(modifier.getKey(), 4, modifier.getOperation(), slot);
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
            case NETHERITE_AXE -> "Hacha de Netherita reforzada";
            case NETHERITE_HOE -> "Azada de Netherita reforzada";
            case NETHERITE_PICKAXE -> "Pico de Netherita reforzada";
            case NETHERITE_SHOVEL -> "Pala de Netherita reforzada";
            case NETHERITE_SWORD -> "Espada de Netherita reforzada";
            case NETHERITE_SPEAR -> "Lanza de Netherita reforzada";
            default -> "Netherita reforzada";
        };
        return NameColor.named(text, ItemRarity.EPIC.color());
    }

    private static void applyTool(ItemStack result) {
        boolean mined = diggingTool(result.getType()) && boostMiningSpeed(result);
        applyToolAttributes(result, !mined);
    }

    private static boolean boostMiningSpeed(ItemStack result) {
        Tool tool = result.getData(DataComponentTypes.TOOL);
        if (tool == null) {
            ItemType type = result.getType().asItemType();
            tool = type == null ? null : type.getDefaultData(DataComponentTypes.TOOL);
        }
        if (tool == null) {
            return false;
        }
        Tool.Builder builder = Tool.tool()
                .defaultMiningSpeed(tool.defaultMiningSpeed())
                .damagePerBlock(tool.damagePerBlock())
                .canDestroyBlocksInCreative(tool.canDestroyBlocksInCreative());
        boolean changed = false;
        for (Tool.Rule rule : tool.rules()) {
            Float speed = rule.speed();
            if (speed != null && Float.isFinite(speed)) {
                builder.addRule(Tool.rule(rule.blocks(), speed + 0.5f, rule.correctForDrops()));
                changed = true;
            } else {
                builder.addRule(rule);
            }
        }
        if (changed) {
            result.setData(DataComponentTypes.TOOL, builder.build());
        }
        return changed;
    }

    private static void applyToolAttributes(ItemStack result, boolean bumpMiningAttribute) {
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        ItemAttributeModifiers existing = result.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        NamespacedKey baseDamage = NamespacedKey.minecraft("base_attack_damage");
        if (existing != null && !existing.modifiers().isEmpty()) {
            boolean hasBase = false;
            for (ItemAttributeModifiers.Entry entry : existing.modifiers()) {
                if (Attribute.ATTACK_DAMAGE.equals(entry.attribute()) && baseDamage.equals(entry.modifier().getKey())) {
                    hasBase = true;
                    break;
                }
            }
            boolean boostedDamage = false;
            boolean boostedMining = false;
            for (ItemAttributeModifiers.Entry entry : existing.modifiers()) {
                AttributeModifier modifier = entry.modifier();
                boolean damage = Attribute.ATTACK_DAMAGE.equals(entry.attribute())
                        && !boostedDamage
                        && (!hasBase || baseDamage.equals(modifier.getKey()));
                boolean mining = bumpMiningAttribute
                        && Attribute.MINING_EFFICIENCY.equals(entry.attribute())
                        && !boostedMining;
                if (damage) {
                    modifier = withAmount(modifier, modifier.getAmount() + 1, entry.getGroup());
                    boostedDamage = true;
                } else if (mining) {
                    modifier = withAmount(modifier, modifier.getAmount() + 0.5, entry.getGroup());
                    boostedMining = true;
                }
                builder.addModifier(entry.attribute(), modifier, entry.getGroup(), entry.display());
            }
        } else {
            ItemType type = result.getType().asItemType();
            if (type == null) {
                return;
            }
            boolean boostedDamage = false;
            boolean boostedMining = false;
            boolean hasBase = false;
            var defaults = type.getDefaultAttributeModifiers().entries();
            for (var entry : defaults) {
                if (Attribute.ATTACK_DAMAGE.equals(entry.getKey()) && baseDamage.equals(entry.getValue().getKey())) {
                    hasBase = true;
                    break;
                }
            }
            for (var entry : defaults) {
                AttributeModifier modifier = entry.getValue();
                EquipmentSlotGroup group = modifier.getSlotGroup() == null ? EquipmentSlotGroup.MAINHAND : modifier.getSlotGroup();
                boolean damage = Attribute.ATTACK_DAMAGE.equals(entry.getKey())
                        && !boostedDamage
                        && (!hasBase || baseDamage.equals(modifier.getKey()));
                boolean mining = bumpMiningAttribute
                        && Attribute.MINING_EFFICIENCY.equals(entry.getKey())
                        && !boostedMining;
                if (damage) {
                    modifier = withAmount(modifier, modifier.getAmount() + 1, group);
                    boostedDamage = true;
                } else if (mining) {
                    modifier = withAmount(modifier, modifier.getAmount() + 0.5, group);
                    boostedMining = true;
                }
                builder.addModifier(entry.getKey(), modifier, group, AttributeModifierDisplay.reset());
            }
        }
        result.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build());
    }

    private static AttributeModifier withAmount(AttributeModifier modifier, double amount, EquipmentSlotGroup group) {
        EquipmentSlotGroup slot = modifier.getSlotGroup() == null ? group : modifier.getSlotGroup();
        return new AttributeModifier(modifier.getKey(), amount, modifier.getOperation(), slot);
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
