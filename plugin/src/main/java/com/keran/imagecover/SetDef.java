package com.keran.imagecover;

import java.util.List;

/** 一个预设：图片列表 + 淡入淡出开关（fade 为 null 表示沿用 config.yml 的全局设置）。 */
public record SetDef(List<PlayItem> items, Boolean fade) {
}
