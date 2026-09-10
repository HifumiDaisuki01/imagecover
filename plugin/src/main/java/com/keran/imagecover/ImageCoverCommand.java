package com.keran.imagecover;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 命令解析（主命令 /icv —— 避开 CMI 等插件占用的 /ic，别名 /imagecover）：
 *   /icv play <玩家|UUID|@a|@p|@r> <图片链接> <时长秒>
 *   /icv play <x> <y> <z> <世界名> <半径> <图片链接> <时长秒>
 *   /icv wgplay <WorldGuard区域名> <图片链接> <时长秒>
 *   /icv setplay <玩家|UUID|@a|@p|@r> <set名称>
 *   /icv setplay <x> <y> <z> <世界名> <半径> <set名称>
 *   /icv setwgplay <WorldGuard区域名> <set名称>
 *   /icv stop
 *   /icv reload
 */
public class ImageCoverCommand implements CommandExecutor, TabCompleter {
	private final ImageCoverPlugin plugin;

	public ImageCoverCommand(ImageCoverPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
							 @NotNull String label, @NotNull String[] args) {
		if (args.length < 1) {
			help(sender);
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		try {
			switch (sub) {
				case "play":
					return handlePlay(sender, args);
				case "wgplay":
					return handleWgPlay(sender, args);
				case "setplay":
					return handleSetPlay(sender, args);
				case "setwgplay":
					return handleSetWgPlay(sender, args);
				case "stop":
					return handleStop(sender);
				case "reload":
					return handleReload(sender);
				default:
					help(sender);
					return true;
			}
		} catch (Exception e) {
			sender.sendMessage("§c执行出错: " + e.getMessage());
			return true;
		}
	}

	// ------------------------------------------------------------------ play

	private boolean handlePlay(CommandSender sender, String[] args) {
		String[] rest = slice(args, 1);
		if (rest.length < 3) {
			sender.sendMessage("§c用法: §f/icv play <玩家|UUID|@a|@p|@r> <图片链接> <时长秒>");
			sender.sendMessage("§f/icv play <x> <y> <z> <世界名> <半径> <图片链接> <时长秒>");
			return true;
		}
		// 区域形式：前 3 个参数为数字
		if (isDouble(rest[0]) && isDouble(rest[1]) && isDouble(rest[2])) {
			if (rest.length < 7) {
				sender.sendMessage("§c区域播放需要: x y z 世界名 半径 图片链接 时长秒");
				return true;
			}
			double x = parseDouble(rest[0]);
			double y = parseDouble(rest[1]);
			double z = parseDouble(rest[2]);
			String worldName = rest[3];
			double radius;
			try {
				radius = parseDouble(rest[4]);
			} catch (NumberFormatException e) {
				sender.sendMessage("§c半径无效: " + rest[4]);
				return true;
			}
			// 区域形式必须带时长；末参不是时长就直接报错，避免把链接末尾数字误判成时长
			long ms;
			try {
				ms = Durations.parseMillis(rest[rest.length - 1]);
			} catch (NumberFormatException e) {
				sender.sendMessage("§c时长无效: " + rest[rest.length - 1]);
				return true;
			}
			// 链接后面多出来的参数只能是"被空格截断的链接"，直接提示而不是静默拼成错误 URL
			if (rest.length - 1 > 5) {
				sender.sendMessage("§c图片链接中疑似含空格（Minecraft 命令无法保留空格）。");
				sender.sendMessage("§7请先对链接做 URL 编码（空格写成 %20），或改用不含空格的直链。");
				return true;
			}
			String url = join(rest, 5, rest.length - 1);
			if (url.isEmpty()) {
				sender.sendMessage("§c图片链接不能为空");
				return true;
			}
			if (ms <= 0) {
				sender.sendMessage("§c时长必须大于 0");
				return true;
			}
			World world = plugin.resolveWorld(worldName);
			if (world == null) {
				sender.sendMessage("§c找不到世界: " + worldName);
				return true;
			}
			List<Player> targets = playersInRadius(world, new Location(world, x, y, z), radius);
			dispatch(sender, targets, List.of(new PlayItem(url, ms)), plugin.fadeMs());
			return true;
		}
		// 玩家形式：<目标> <图片链接> <时长秒>
		String target = rest[0];
		long ms;
		try {
			ms = Durations.parseMillis(rest[rest.length - 1]);
		} catch (NumberFormatException e) {
			sender.sendMessage("§c时长无效: " + rest[rest.length - 1]);
			return true;
		}
		if (rest.length - 1 > 2) {
			sender.sendMessage("§c图片链接中疑似含空格（Minecraft 命令无法保留空格）。");
			sender.sendMessage("§7请先对链接做 URL 编码（空格写成 %20），或改用不含空格的直链。");
			return true;
		}
		String url = join(rest, 1, rest.length - 1);
		if (url.isEmpty()) {
			sender.sendMessage("§c图片链接不能为空");
			return true;
		}
		if (ms <= 0) {
			sender.sendMessage("§c时长必须大于 0");
			return true;
		}
		List<Player> targets = resolveTargets(sender, target);
		dispatch(sender, targets, List.of(new PlayItem(url, ms)), plugin.fadeMs());
		return true;
	}

	// ---------------------------------------------------------------- wgplay

	private boolean handleWgPlay(CommandSender sender, String[] args) {
		String[] rest = slice(args, 1);
		if (rest.length < 3) {
			sender.sendMessage("§c用法: §f/icv wgplay <WorldGuard区域名> <图片链接> <时长秒>");
			return true;
		}
		String regionName = rest[0];
		long ms;
		try {
			ms = Durations.parseMillis(rest[rest.length - 1]);
		} catch (NumberFormatException e) {
			sender.sendMessage("§c时长无效: " + rest[rest.length - 1]);
			return true;
		}
		if (rest.length - 1 > 2) {
			sender.sendMessage("§c图片链接中疑似含空格（Minecraft 命令无法保留空格）。");
			sender.sendMessage("§7请先对链接做 URL 编码（空格写成 %20），或改用不含空格的直链。");
			return true;
		}
		String url = join(rest, 1, rest.length - 1);
		if (url.isEmpty() || ms <= 0) {
			sender.sendMessage("§c图片链接不能为空、时长必须大于 0");
			return true;
		}
		List<Player> targets = playersInWgRegion(sender, regionName);
		dispatch(sender, targets, List.of(new PlayItem(url, ms)), plugin.fadeMs());
		return true;
	}

	// --------------------------------------------------------------- setplay

	private boolean handleSetPlay(CommandSender sender, String[] args) {
		String[] rest = slice(args, 1);
		if (rest.length < 2) {
			sender.sendMessage("§c用法: §f/icv setplay <玩家|UUID|@a|@p|@r> <set名称>");
			sender.sendMessage("§f/icv setplay <x> <y> <z> <世界名> <半径> <set名称>");
			return true;
		}
		// 区域形式：前 3 个参数为数字（6 参时先判世界名，避免把名为 "1" 的玩家误判成区域）
		if (rest.length == 6 && isDouble(rest[0]) && isDouble(rest[1]) && isDouble(rest[2])
				&& plugin.resolveWorld(rest[3]) == null) {
			sender.sendMessage("§c找不到世界: " + rest[3]);
			return true;
		}
		if (isDouble(rest[0]) && isDouble(rest[1]) && isDouble(rest[2])) {
			if (rest.length < 6) {
				sender.sendMessage("§c区域播放需要: x y z 世界名 半径 set名称");
				return true;
			}
			double x = parseDouble(rest[0]);
			double y = parseDouble(rest[1]);
			double z = parseDouble(rest[2]);
			String worldName = rest[3];
			double radius;
			try {
				radius = parseDouble(rest[4]);
			} catch (NumberFormatException e) {
				sender.sendMessage("§c半径无效: " + rest[4]);
				return true;
			}
			String setName = rest[5];
			SetDef def = lookupSet(sender, setName);
			if (def == null) return true;
			World world = plugin.resolveWorld(worldName);
			if (world == null) {
				sender.sendMessage("§c找不到世界: " + worldName);
				return true;
			}
			List<Player> targets = playersInRadius(world, new Location(world, x, y, z), radius);
			dispatch(sender, targets, def.items(), setFadeMs(def));
			return true;
		}
		// 玩家形式：<目标> <set名称>
		String target = rest[0];
		String setName = rest[1];
		SetDef def = lookupSet(sender, setName);
		if (def == null) return true;
		List<Player> targets = resolveTargets(sender, target);
		dispatch(sender, targets, def.items(), setFadeMs(def));
		return true;
	}

	// ------------------------------------------------------------- setwgplay

	private boolean handleSetWgPlay(CommandSender sender, String[] args) {
		String[] rest = slice(args, 1);
		if (rest.length < 2) {
			sender.sendMessage("§c用法: §f/icv setwgplay <WorldGuard区域名> <set名称>");
			return true;
		}
		String regionName = rest[0];
		String setName = rest[1];
		SetDef def = lookupSet(sender, setName);
		if (def == null) return true;
		List<Player> targets = playersInWgRegion(sender, regionName);
		dispatch(sender, targets, def.items(), setFadeMs(def));
		return true;
	}

	// ------------------------------------------------------------ stop/reload

	private boolean handleStop(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("§c/icv stop 只能由玩家执行（用于停止自己屏幕上的图片）");
			return true;
		}
		plugin.sendStop(player);
		sender.sendMessage("§a已停止你屏幕上的图片");
		return true;
	}

	private boolean handleReload(CommandSender sender) {
		plugin.reloadConfig();
		plugin.setManager().reload();
		sender.sendMessage("§aImageCover: config.yml 与 set.yml 已重载");
		return true;
	}

	// ------------------------------------------------------------------ 目标

	/** 玩家解析：@a/@p/@r（+all 别名）→ 精确名(忽略大小写) → UUID；支持 PAPI 占位符展开 */
	private List<Player> resolveTargets(CommandSender sender, String target) {
		String resolved = PlaceholderApiBridge.setPlaceholders(sender, target);
		String low = resolved.toLowerCase(Locale.ROOT);
		switch (low) {
			case "@a":
			case "all":
				return new ArrayList<>(Bukkit.getOnlinePlayers());
			case "@p": {
				if (sender instanceof Player p) return List.of(p);
				sender.sendMessage("§c@p 需要由玩家执行（控制台请用 @a 或指定玩家）");
				return null;
			}
			case "@r": {
				List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
				if (online.isEmpty()) {
					sender.sendMessage("§c没有在线玩家");
					return null;
				}
				return List.of(online.get((int) (Math.random() * online.size())));
			}
			default: {
				Player player = resolvePlayer(resolved);
				if (player == null) {
					sender.sendMessage("§c找不到玩家: " + target);
					return null;
				}
				return List.of(player);
			}
		}
	}

	private Player resolvePlayer(String s) {
		Player p = Bukkit.getPlayerExact(s);
		if (p != null) return p;
		for (Player online : Bukkit.getOnlinePlayers()) {
			if (online.getName().equalsIgnoreCase(s)) return online;
		}
		try {
			UUID uuid = UUID.fromString(s);
			return Bukkit.getPlayer(uuid);
		} catch (Exception ignored) {
		}
		return null;
	}

	/** 半径范围（同世界、球体距离） */
	private List<Player> playersInRadius(World world, Location center, double radius) {
		List<Player> targets = new ArrayList<>();
		double r2 = radius * radius;
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (!p.getWorld().equals(world)) continue;
			Location l = p.getLocation();
			double dx = l.getX() - center.getX();
			double dy = l.getY() - center.getY();
			double dz = l.getZ() - center.getZ();
			if (dx * dx + dy * dy + dz * dz <= r2) targets.add(p);
		}
		return targets;
	}

	/** WorldGuard 区域：逐玩家用其所在世界的 RegionManager 判定（多世界同名区域互不影响） */
	private List<Player> playersInWgRegion(CommandSender sender, String regionName) {
		if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
			sender.sendMessage("§c未安装 WorldGuard，无法使用区域播放");
			return null;
		}
		List<Player> targets = new ArrayList<>();
		String id = regionName.toLowerCase(Locale.ROOT);
		try {
			for (Player p : Bukkit.getOnlinePlayers()) {
				RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer()
						.get(BukkitAdapter.adapt(p.getWorld()));
				if (rm == null) continue;
				ProtectedRegion region = rm.getRegion(id);
				if (region == null) continue;
				Location l = p.getLocation();
				if (region.contains(BlockVector3.at(l.getBlockX(), l.getBlockY(), l.getBlockZ()))) {
					targets.add(p);
				}
			}
		} catch (Throwable t) {
			sender.sendMessage("§cWorldGuard 区域查询失败: " + t.getMessage());
			return null;
		}
		return targets;
	}

	private SetDef lookupSet(CommandSender sender, String name) {
		SetDef def = plugin.setManager().get(name);
		if (def == null || def.items().isEmpty()) {
			sender.sendMessage("§c找不到预设: " + name);
			Set<String> names = plugin.setManager().names();
			if (!names.isEmpty()) sender.sendMessage("§7可用预设: §f" + String.join(", ", names));
			return null;
		}
		return def;
	}

	/** 计算某个预设的淡入淡出时长：set 内 fade 未写则沿用 config.yml 全局设置 */
	private int setFadeMs(SetDef def) {
		Boolean f = def.fade();
		if (f == null) return plugin.fadeMs();
		return f ? plugin.fadeMs() : 0;
	}

	/** targets 为 null 表示已提示错误；为空表示范围内无人 */
	private void dispatch(CommandSender sender, List<Player> targets, List<PlayItem> items, int fadeMs) {
		if (targets == null || items == null || items.isEmpty()) return;
		if (targets.isEmpty()) {
			sender.sendMessage("§7范围内没有玩家（或目标玩家不在线）");
			return;
		}
		int sent = 0, failed = 0;
		for (Player p : targets) {
			if (plugin.sendPlay(p, items, fadeMs)) sent++;
			else failed++;
		}
		if (sent == 0) {
			sender.sendMessage("§c没有成功发送（可能是播放列表过大）");
		} else {
			sender.sendMessage("§a已向 " + sent + " 名玩家发送播放指令（共 " + items.size() + " 张）"
					+ (failed > 0 ? " §7(失败 " + failed + ")" : ""));
		}
	}

	// ------------------------------------------------------------------ 工具

	private void help(CommandSender sender) {
		sender.sendMessage("§6ImageCover §7- 全屏图片演出");
		sender.sendMessage("§f/icv play <玩家|UUID|@a|@p|@r> <图片链接> <时长秒>");
		sender.sendMessage("§f/icv play <x> <y> <z> <世界名> <半径> <图片链接> <时长秒>");
		sender.sendMessage("§f/icv wgplay <WorldGuard区域名> <图片链接> <时长秒>");
		sender.sendMessage("§f/icv setplay <玩家|UUID|@a|@p|@r> <set名称>");
		sender.sendMessage("§f/icv setplay <x> <y> <z> <世界名> <半径> <set名称>");
		sender.sendMessage("§f/icv setwgplay <WorldGuard区域名> <set名称>");
		sender.sendMessage("§f/icv stop §7(停止自己屏幕上的图片)   §f/icv reload §7(重载 set.yml)");
	}

	private static String[] slice(String[] args, int from) {
		String[] out = new String[Math.max(0, args.length - from)];
		if (out.length > 0) System.arraycopy(args, from, out, 0, out.length);
		return out;
	}

	private static String join(String[] args, int from, int toExclusive) {
		StringBuilder sb = new StringBuilder();
		for (int i = from; i < toExclusive; i++) {
			if (i > from) sb.append(' ');
			sb.append(args[i]);
		}
		return sb.toString().trim();
	}

	private static boolean isDouble(String s) {
		try {
			Double.parseDouble(s.trim());
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static double parseDouble(String s) {
		return Double.parseDouble(s.trim());
	}

	/** 是否是一个合法的时长（秒，容忍结尾 s），用于识别被空格截断的链接 */
	private static boolean isDuration(String s) {
		try {
			Durations.parseMillis(s);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	// ---------------------------------------------------------------- 补全

	@Override
	public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
									  @NotNull String alias, @NotNull String[] args) {
		List<String> out = new ArrayList<>();
		if (args.length == 1) {
			addFiltered(out, args[0], List.of("play", "setplay", "wgplay", "setwgplay", "stop", "reload"));
			return out;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "play":
				if (args.length == 2) addFiltered(out, args[1], selectorsAndPlayers());
				break;
			case "setplay":
				if (args.length == 2) addFiltered(out, args[1], selectorsAndPlayers());
				else if (args.length == 3) addFiltered(out, args[2], new ArrayList<>(plugin.setManager().names()));
				break;
			case "setwgplay":
				if (args.length == 3) addFiltered(out, args[2], new ArrayList<>(plugin.setManager().names()));
				break;
			default:
				break;
		}
		return out;
	}

	private static List<String> selectorsAndPlayers() {
		List<String> list = new ArrayList<>(List.of("@a", "@p", "@r", "all"));
		for (Player p : Bukkit.getOnlinePlayers()) list.add(p.getName());
		return list;
	}

	private static void addFiltered(List<String> out, String prefix, List<String> candidates) {
		String p = prefix.toLowerCase(Locale.ROOT);
		for (String c : candidates) {
			if (c.toLowerCase(Locale.ROOT).startsWith(p)) out.add(c);
		}
	}
}
