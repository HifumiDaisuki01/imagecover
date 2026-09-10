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
 * 把全屏图片画在「所有 HUD 之后」，解决图片被聊天框/侧边栏「挖掉一块」的问题。
 *
 * <h2>问题根源</h2>
 * Fabric 的 {@code HudRenderCallback} 触发点在 {@code Gui.render()} 内部、
 * <b>聊天框渲染之前</b>，所以左下角一弹出聊天消息就会盖住图片，
 * 看起来像图片被「挖掉一块」；计分板侧边栏、Tab 玩家列表同理。
 *
 * <h2>Gui.render 的真实渲染顺序（1.20.1 字节码实测）</h2>
 * <pre>
 *   1421: ChatComponent.render(GuiGraphics, ...)       ← 聊天框
 *   1518: PlayerTabOverlay.render(GuiGraphics, ...)    ← Tab 玩家列表
 *   1534: renderSavingIndicator(GuiGraphics)           ← 自动保存指示器
 *   1537: return
 * </pre>
 *
 * <h2>为什么不用 GameRenderer</h2>
 * 曾尝试注入 {@code GameRenderer.render} 中调用 {@code Gui.render} 之后的位&#32622;，
 * 但 {@code GameRenderer} 存在<b>同名重载</b>（{@code render(float,long,boolean)}
 * 与 {@code render(GuiGraphics,float)}），Mixin 会因方法描述符歧义而注入失败，
 * 导致 {@code GameRenderer} 整类加载崩溃。因此回到 {@code Gui} 内部注入。
 *
 * <h2>注入策略：主锚点 + 兜底</h2>
 * <ol>
 *   <li><b>主锚点</b>：锚定 Tab 玩家列表渲染调用之后 —— 它是 {@code Gui.render} 里
 *       最后一个可变内容的 HUD 元素，名字唯一、无重载歧义，语义上「所有 HUD 之后」；</li>
 *   <li><b>兜底</b>：再挂一个 {@code TAIL}，防止未来小版本结构调整导致主锚点失配。</li>
 * </ol>
 *
 * <p>两处都使用 {@code require = 0}，并且 {@link ImageOverlay} 内部有「本帧只画一次」
 * 的守卫，因此即便两个注入点同时命中也不会重复绘制，任一失配也不会导致游戏崩溃。</p>
 *
 * <h2>副作用</h2>
 * 图片全屏不透明，演出期间会盖住包括聊天、计分板在内的整个界面 —— 这正是
 * 「全屏演出」的预期效果。玩家按键操作仍正常，只是视觉被盖住，不影响交互。
 */
@Mixin(Gui.class)
public class GuiMixin {

	/**
	 * 主锚点：自动保存指示器渲染之后。
	 *
	 * <p>在 {@code Gui.render} 内部，渲染顺序为：
	 * 聊天框（1421）→ Tab 玩家列表（1518）→ <b>自动保存指示器</b>（1534）→ return（1537）。
	 * 因此锚定「自动保存指示器之后」= 所有 HUD 内容之后，图片必然盖在最上层。</p>
	 *
	 * <p><b>为什么选它做锚点</b>：其签名为
	 * {@code renderSavingIndicator(GuiGraphics)}（mappings.tiny: {@code m (Leox;)V g method_39192}），
	 * <b>只有一个参数</b>，不存在任何重载或参数顺序歧义，refmap 解析 100% 可靠。</p>
	 *
	 * <p>对比：曾尝试锚定 {@code PlayerTabOverlay.render(GuiGraphics,int,Objective,Scoreboard)}，
	 * 但 {@code Objective}（class_269）与 {@code Scoreboard}（class_266）互相引用，
	 * Mixin 生成的 refmap 会把两者顺序写反，导致运行时注入失配。故改用本锚点。</p>
	 */
	@Inject(
			method = "render",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Gui;renderSavingIndicator(Lnet/minecraft/client/gui/GuiGraphics;)V",
					shift = At.Shift.AFTER
			),
			require = 0
	)
	private void imagecover$renderAfterTabList(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
		ImageOverlay.INSTANCE.renderTopmost(guiGraphics);
	}

	/**
	 * 兜底锚点：方法末尾。
	 *
	 * <p>即便主锚点因版本差异失配，这里也能保证图片在最上层绘制。</p>
	 */
	@Inject(method = "render", at = @At("TAIL"), require = 0)
	private void imagecover$renderAtTail(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
		ImageOverlay.INSTANCE.renderTopmost(guiGraphics);
	}
}
