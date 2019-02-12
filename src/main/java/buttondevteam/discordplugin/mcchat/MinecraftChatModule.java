package buttondevteam.discordplugin.mcchat;

import buttondevteam.core.component.channel.Channel;
import buttondevteam.discordplugin.DPUtils;
import buttondevteam.discordplugin.DiscordConnectedPlayer;
import buttondevteam.discordplugin.DiscordPlugin;
import buttondevteam.lib.TBMCCoreAPI;
import buttondevteam.lib.architecture.Component;
import buttondevteam.lib.architecture.ConfigData;
import com.google.common.collect.Lists;
import lombok.Getter;
import lombok.val;
import org.bukkit.Bukkit;
import sx.blah.discord.handle.obj.IChannel;

import java.util.ArrayList;
import java.util.UUID;

public class MinecraftChatModule extends Component {
	private @Getter MCChatListener listener;

	public MCChatListener getListener() { //It doesn't want to generate
		return listener;
	}

	public ConfigData<ArrayList<String>> whitelistedCommands() {
		return getConfig().getData("whitelistedCommands", () -> Lists.newArrayList("list", "u", "shrug", "tableflip", "unflip", "mwiki",
			"yeehaw", "lenny", "rp", "plugins"));
	}

	public ConfigData<IChannel> chatChannel() {
		return DPUtils.channelData(getConfig(), "chatChannel", 239519012529111040L);
	}

	@Override
	protected void enable() {
		listener = new MCChatListener(this);
		DiscordPlugin.dc.getDispatcher().registerListener(listener);
		TBMCCoreAPI.RegisterEventsForExceptions(listener, getPlugin());
		TBMCCoreAPI.RegisterEventsForExceptions(new MCListener(), getPlugin());//These get undone if restarting/resetting - it will ignore events if disabled

		val chcons = getConfig().getConfig().getConfigurationSection("chcons");
		if (chcons == null) //Fallback to old place
			getConfig().getConfig().getRoot().getConfigurationSection("chcons");
		if (chcons != null) {
			val chconkeys = chcons.getKeys(false);
			for (val chconkey : chconkeys) {
				val chcon = chcons.getConfigurationSection(chconkey);
				val mcch = Channel.getChannels().filter(ch -> ch.ID.equals(chcon.getString("mcchid"))).findAny();
				val ch = DiscordPlugin.dc.getChannelByID(chcon.getLong("chid"));
				val did = chcon.getLong("did");
				val user = DiscordPlugin.dc.fetchUser(did);
				val groupid = chcon.getString("groupid");
				val toggles = chcon.getInt("toggles");
				if (!mcch.isPresent() || ch == null || user == null || groupid == null)
					continue;
				Bukkit.getScheduler().runTask(getPlugin(), () -> { //<-- Needed because of occasional ConcurrentModificationExceptions when creating the player (PermissibleBase)
					val dcp = new DiscordConnectedPlayer(user, ch, UUID.fromString(chcon.getString("mcuid")), chcon.getString("mcname"));
					MCChatCustom.addCustomChat(ch, groupid, mcch.get(), user, dcp, toggles);
				});
			}
		}
	}

	@Override
	protected void disable() {
		val chcons = MCChatCustom.getCustomChats();
		val chconsc = getConfig().getConfig().createSection("chcons");
		for (val chcon : chcons) {
			val chconc = chconsc.createSection(chcon.channel.getStringID());
			chconc.set("mcchid", chcon.mcchannel.ID);
			chconc.set("chid", chcon.channel.getLongID());
			chconc.set("did", chcon.user.getLongID());
			chconc.set("mcuid", chcon.dcp.getUniqueId().toString());
			chconc.set("mcname", chcon.dcp.getName());
			chconc.set("groupid", chcon.groupID);
			chconc.set("toggles", chcon.toggles);
		}
		MCChatListener.stop(true);
	} //TODO: Use ComponentManager.isEnabled() at other places too, instead of SafeMode
}