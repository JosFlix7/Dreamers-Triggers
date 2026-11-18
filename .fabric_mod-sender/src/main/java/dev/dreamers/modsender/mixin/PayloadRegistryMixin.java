package dev.dreamers.modsender.mixin;

import dev.dreamers.modsender.ModListPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

@Mixin(ServerboundCustomPayloadPacket.class)
public class PayloadRegistryMixin {
    @Inject(method = "lambda$static$1", at = @At("HEAD"), remap = false)
    private static void dreamers$register(ArrayList<CustomPacketPayload.TypeAndCodec<FriendlyByteBuf, ?>> types, CallbackInfo ci) {
        types.add(new CustomPacketPayload.TypeAndCodec<>(ModListPayload.TYPE, ModListPayload.CODEC));
    }
}
