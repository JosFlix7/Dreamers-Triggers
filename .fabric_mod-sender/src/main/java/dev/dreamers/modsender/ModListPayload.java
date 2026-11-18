package dev.dreamers.modsender;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

public record ModListPayload(byte[] body) implements CustomPacketPayload {
    public static final Type<ModListPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("dreamers", "mods"));
    public static final StreamCodec<FriendlyByteBuf, ModListPayload> CODEC = CustomPacketPayload.codec(
            (payload, buf) -> buf.writeBytes(payload.body),
            buf -> {
                byte[] body = new byte[buf.readableBytes()];
                buf.readBytes(body);
                return new ModListPayload(body);
            }
    );

    public static ModListPayload fromLoader() {
        StringBuilder builder = new StringBuilder();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            if (mod.getContainingMod().isPresent()) {
                continue;
            }
            String id = mod.getMetadata().getId();
            if (id == null || id.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(id);
        }
        return new ModListPayload(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
