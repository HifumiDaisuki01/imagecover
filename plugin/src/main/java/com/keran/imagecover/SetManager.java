package com.keran.imagecover;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * set.yml 预设播放列表管理。
 * 支持两种写法：
 *   A) 直接列表（淡入淡出沿用 config.yml 全局设置）：
 *        set名:
 *          - url: "http://.../1.jpg"
 *            duration: 1.1
 *          - "http://.../2.jpg 8.3s"
 *   B) 带独立淡入淡出开关：
 *        set名:
 *          fade: false
 *          images:
 *            - url: "http://.../1.jpg"
 *              duration: 1.1
 */
public class SetManager {
	private final ImageCoverPlugin plugin;
	/** key 统一小写，便于大小写不敏感查询 */
	private final Map<String, SetDef> sets = new HashMap<>();

	public SetManager(ImageCoverPlugin plugin) {
		this.plugin = plugin;
	}

	/** 重载 set.yml（不存在时先落盘示例） */
	public void reload() {
		sets.clear();
		File file = new File(plugin.getDataFolder(), "set.yml");
		if (!file.exists()) {
			plugin.saveResource("set.yml", false);
		}
		YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
		for (String key : cfg.getKeys(false)) {
			Boolean fade = null;
			List<?> list = null;
			Object node = cfg.get(key);
			if (node instanceof ConfigurationSection sec) {
				if (sec.isSet("fade")) fade = sec.getBoolean("fade");
				if (sec.isList("images")) list = sec.getList("images");
				else if (sec.isList("frames")) list = sec.getList("frames");
			} else if (node instanceof List<?> l) {
				list = l;
			}
			if (list == null) continue;
			List<PlayItem> items = new ArrayList<>();
			for (Object raw : list) {
				PlayItem item = parseItem(raw);
				if (item != null) items.add(item);
				else plugin.getLogger().warning("set.yml: 预设 '" + key + "' 中存在无法解析的条目: " + raw);
			}
			if (!items.isEmpty()) sets.put(key.toLowerCase(Locale.ROOT), new SetDef(items, fade));
		}
		plugin.getLogger().info("已加载 " + sets.size() + " 个预设 (set.yml)");
	}

	/** 取预设（大小写不敏感）；不存在返回 null */
	public SetDef get(String name) {
		if (name == null) return null;
		return sets.get(name.toLowerCase(Locale.ROOT));
	}

	public Set<String> names() {
		return sets.keySet();
	}

	private PlayItem parseItem(Object raw) {
		if (raw instanceof String s) {
			return parseShorthand(s);
		}
		if (raw instanceof Map<?, ?> map) {
			Object urlObj = map.get("url");
			if (urlObj == null) return null;
			String url = String.valueOf(urlObj).trim();
			Object durObj = map.get("duration");
			long ms;
			try {
				ms = durObj == null ? 3000L : Durations.parseMillis(String.valueOf(durObj));
			} catch (NumberFormatException e) {
				return null;
			}
			if (url.isEmpty() || ms <= 0) return null;
			return new PlayItem(url, ms);
		}
		return null;
	}

	private PlayItem parseShorthand(String s) {
		String t = s.trim();
		int idx = t.lastIndexOf(' ');
		if (idx <= 0 || idx == t.length() - 1) return null;
		String url = t.substring(0, idx).trim();
		String dur = t.substring(idx + 1).trim();
		long ms;
		try {
			ms = Durations.parseMillis(dur);
		} catch (NumberFormatException e) {
			return null;
		}
		if (url.isEmpty() || ms <= 0) return null;
		return new PlayItem(url, ms);
	}
}
