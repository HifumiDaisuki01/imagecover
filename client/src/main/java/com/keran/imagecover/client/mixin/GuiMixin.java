/*
 * ImageCover Client - 全屏图片演出（客户端）
 * (c) 2026 KeranTechnology - http://tech.keran.cc
 */
package com.keran.imagecover.client.mixin;

import com.keran.imagecover.client.ImageOverlay;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把全屏图片画在「所有 HUD 之后」。
 *
 * <h2>为什么需要这个 Mixin</h2>
 * 原先用 Fabric 的 {@code HudRenderCallback} 渲染，它的触发点在
 * {@code Gui.render()} 内部、**聊天框渲染之前**，所以左下角一旦弹出聊天消息，
 * 聊天框就会盖在图片上面 —— 玩家看到图片被「切掉一块」。
 *
 * <p>尝试过的替代方案都不行：
 * <ul>
 *   <li>{@code WorldRenderEvents.LAST} —— 它同样在 HUD 之前触发，聊天框依然会盖住；</li>
 *   <li>调整 HUD 注册顺序 —— HUD 层内没有「置顶」能力，聊天框始终最后画。</li>
 * </ul>
 *
 * <p>唯一可靠的做法是注入 {@code Gui.render()} 的 <b>TAIL</b>（方法末尾）。
 * 此时 includeChat 的聊天框、计分板侧边栏、Tab 玩家列表等全部画完，
 * 在这里绘制就能保证图片盖在最上层。</p>
 *
 * <h2>副作用</h2>
 * 图片是全屏不透明的，所以演出期间会盖住整个游戏界面（包括聊天和计分板）。
 * 这正是「全屏演出」的预期效果；玩家按 Esc 等操作仍然正常，
 * 只是视觉上被盖住，不会影响操作。
 */
@Mixin(Gui.class)
public class GuiMixin {

	/**
	 * 注入点选在 {@code render} 方法末尾。
	 *
	 * <p>用 {@code At("TAIL")} 而不是指定某个局部变量或方法调用，
	 * 这样不会因为原版方法内部结构调整而失效，跨小版本更稳。</p>
	 */
	@Inject(method = "render", at = @At("TAIL"))
	private void imagecover$renderOverlayOnTop(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
		ImageOverlay.INSTANCE.renderTopmost(guiGraphics);
	}
}
