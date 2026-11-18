package dev.dreamers.modsender.mixin;

import dev.dreamers.modsender.ModListPayload;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ConfigurationSenderMixin {
    @Shadow
    public abstract void send(Packet<?> packet);

    @Inject(method = "handleCustomPayload", at = @At("RETURN"))
    private void dreamers$sendList(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (!((Object) this instanceof ClientConfigurationPacketListenerImpl)) {
            return;
        }
        if (!(packet.payload() instanceof BrandPayload)) {
            return;
        }
        send(new ServerboundCustomPayloadPacket(ModListPayload.fromLoader()));
    }
}
