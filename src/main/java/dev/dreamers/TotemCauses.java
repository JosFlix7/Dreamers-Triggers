package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;

final class TotemCauses {
    private record Phrase(String alone, String withAttacker) {}

    private static final Phrase UNKNOWN = new Phrase("Daño", null);
    private static final Map<String, Phrase> PHRASES = Map.ofEntries(
            Map.entry("fall", new Phrase("Daño de caída", null)),
            Map.entry("drown", new Phrase("Se ahogó", null)),
            Map.entry("lava", new Phrase("Intentó nadar en lava", null)),
            Map.entry("in_fire", new Phrase("Se prendió fuego", null)),
            Map.entry("on_fire", new Phrase("Ardió", null)),
            Map.entry("hot_floor", new Phrase("Piso de magma", null)),
            Map.entry("campfire", new Phrase("Fogata", null)),
            Map.entry("cactus", new Phrase("Cactus", null)),
            Map.entry("sweet_berry_bush", new Phrase("Arbusto de bayas dulces", null)),
            Map.entry("starve", new Phrase("Hambre", null)),
            Map.entry("cramming", new Phrase("Aplastamiento", null)),
            Map.entry("fly_into_wall", new Phrase("Energía cinética", null)),
            Map.entry("freeze", new Phrase("Se congeló", null)),
            Map.entry("out_of_world", new Phrase("Cayó al vacío", null)),
            Map.entry("outside_border", new Phrase("Fuera del borde del mundo", null)),
            Map.entry("lightning_bolt", new Phrase("Rayo", null)),
            Map.entry("wither", new Phrase("Efecto Wither", null)),
            Map.entry("dragon_breath", new Phrase("Aliento de dragón", null)),
            Map.entry("dry_out", new Phrase("Deshidratación", null)),
            Map.entry("falling_anvil", new Phrase("Yunque", null)),
            Map.entry("falling_block", new Phrase("Bloque que caía", null)),
            Map.entry("falling_stalactite", new Phrase("Estalactita", null)),
            Map.entry("stalagmite", new Phrase("Estalagmita", null)),
            Map.entry("bad_respawn_point", new Phrase("Punto de reaparición", null)),
            Map.entry("in_wall", new Phrase("Sofocación", null)),
            Map.entry("ender_pearl", new Phrase("Perla de ender", null)),
            Map.entry("sulfur_cube_hot", new Phrase("Cubo de azufre caliente", null)),
            Map.entry("generic", new Phrase("Daño", null)),
            Map.entry("explosion", new Phrase("Explosión", "Detonado(a) por ")),
            Map.entry("player_explosion", new Phrase("Explosión", "Detonado(a) por ")),
            Map.entry("mob_attack", new Phrase("Ataque", "Asesinado(a) por ")),
            Map.entry("mob_attack_no_aggro", new Phrase("Ataque", "Asesinado(a) por ")),
            Map.entry("player_attack", new Phrase("Ataque", "Asesinado(a) por ")),
            Map.entry("generic_kill", new Phrase("Ataque", "Asesinado(a) por ")),
            Map.entry("arrow", new Phrase("Flecha", "Flecha de ")),
            Map.entry("trident", new Phrase("Tridente", "Tridente de ")),
            Map.entry("spear", new Phrase("Lanza", "Lanza de ")),
            Map.entry("mob_projectile", new Phrase("Proyectil", "Proyectil de ")),
            Map.entry("thrown", new Phrase("Proyectil", "Proyectil de ")),
            Map.entry("fireball", new Phrase("Bola de fuego", "Bola de fuego de ")),
            Map.entry("unattributed_fireball", new Phrase("Bola de fuego", "Bola de fuego de ")),
            Map.entry("wither_skull", new Phrase("Calavera de Wither", "Calavera de ")),
            Map.entry("fireworks", new Phrase("Fuegos artificiales", "Fuegos artificiales de ")),
            Map.entry("magic", new Phrase("Magia", "Magia de ")),
            Map.entry("indirect_magic", new Phrase("Magia", "Magia de ")),
            Map.entry("thorns", new Phrase("Espinas", "Espinas de ")),
            Map.entry("sting", new Phrase("Picadura", "Picadura de ")),
            Map.entry("sonic_boom", new Phrase("Explosión sónica", "Explosión sónica de ")),
            Map.entry("mace_smash", new Phrase("Mazazo", "Mazazo de ")),
            Map.entry("wind_charge", new Phrase("Carga de viento", "Carga de viento de ")),
            Map.entry("spit", new Phrase("Escupitajo", "Escupitajo de "))
    );

    private TotemCauses() {}

    static Component describe(DamageSource source) {
        if (source == null) {
            return text(UNKNOWN.alone);
        }
        Phrase phrase = PHRASES.getOrDefault(source.getDamageType().getKey().getKey(), UNKNOWN);
        Entity attacker = source.getCausingEntity();
        if (phrase.withAttacker != null && attacker != null) {
            return text(phrase.withAttacker).append(entityLabel(attacker));
        }
        return text(phrase.alone);
    }

    private static Component entityLabel(Entity entity) {
        if (entity instanceof Player player) {
            return player.displayName();
        }
        Component custom = entity.customName();
        if (custom != null) {
            return custom;
        }
        return Component.translatable(entity.getType().translationKey());
    }

    private static Component text(String value) {
        return Component.text(value, NamedTextColor.YELLOW);
    }
}
