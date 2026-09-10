package com.keran.imagecover;

/**
 * 时长解析工具：命令行与 set.yml 共用。
 * 支持 "1.1" / "1.1s" / "8" / "8s" 等写法（秒），解析时可容忍结尾的 s。
 */
public final class Durations {
	private Durations() {}

	/** 解析秒数；失败抛 NumberFormatException */
	public static double parseSeconds(String s) {
		if (s == null) throw new NumberFormatException("null");
		String t = s.trim();
		if (t.endsWith("s") || t.endsWith("S")) {
			t = t.substring(0, t.length() - 1).trim();
		}
		return Double.parseDouble(t);
	}

	/** 秒 -> 毫秒（四舍五入） */
	public static long toMillis(double seconds) {
		return Math.round(seconds * 1000.0);
	}

	/** 解析并转毫秒；失败抛 NumberFormatException */
	public static long parseMillis(String s) {
		return toMillis(parseSeconds(s));
	}
}
