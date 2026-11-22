package dev.polarclient.impl.mixin;

import dev.polarclient.Polar;
import net.minecraft.client.ClientClockManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Time & Weather: the overworld clock reads the chosen time of day (the client's own copy only). */
@Mixin(ClientClockManager.class)
public abstract class ClientClockInstanceMixin {
	@Inject(method = "getTotalTicks", at = @At("RETURN"), cancellable = true)
	private void polar$time(Holder<WorldClock> clock, CallbackInfoReturnable<Long> cir) {
		if (Polar.modules() == null) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return;
		long fixed = Polar.modules().timeWeather.dayTime();
		if (fixed >= 0) cir.setReturnValue(Math.floorDiv(cir.getReturnValue(), 24000L) * 24000L + fixed);
	}
}
