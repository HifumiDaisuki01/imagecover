package com.keran.imagecover;

/**
 * 一张待播放的图片：链接 + 显示时长（毫秒）。
 */
public record PlayItem(String url, long durationMs) {
}
