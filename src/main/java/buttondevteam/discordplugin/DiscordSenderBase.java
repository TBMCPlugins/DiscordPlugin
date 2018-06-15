package buttondevteam.discordplugin;

import buttondevteam.lib.TBMCCoreAPI;
import buttondevteam.lib.chat.Channel;
import buttondevteam.lib.chat.IDiscordSender;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import sx.blah.discord.handle.obj.IChannel;
import sx.blah.discord.handle.obj.IUser;

import java.util.Arrays;
import java.util.stream.Collectors;

public abstract class DiscordSenderBase implements IDiscordSender {
	/**
	 * May be null.
	 */
	protected IUser user;
	protected IChannel channel;
	private @Getter @Setter @NonNull Channel mcchannel = Channel.GlobalChat;

	protected DiscordSenderBase(IUser user, IChannel channel) {
		this.user = user;
		this.channel = channel;
	}

	private volatile String msgtosend = "";
	private volatile BukkitTask sendtask;

	/**
	 * Returns the user. May be null.
	 * 
	 * @return The user or null.
	 */
	public IUser getUser() {
		return user;
	}

	public IChannel getChannel() {
		return channel;
	}

	@Override
	public void sendMessage(String message) {
		try {
			final boolean broadcast = new Exception().getStackTrace()[2].getMethodName().contains("broadcast");
			if (broadcast)
				return;
			final String sendmsg = DPUtils.sanitizeString(message);
			msgtosend += "\n" + sendmsg;
			if (sendtask == null)
				sendtask = Bukkit.getScheduler().runTaskLaterAsynchronously(DiscordPlugin.plugin, () -> {
					DiscordPlugin.sendMessageToChannel(channel,
							(!broadcast && user != null ? user.mention() + "\n" : "") + msgtosend.trim());
					sendtask = null;
					msgtosend = "";
				}, 10); // Waits a half second to gather all/most of the different messages
		} catch (Exception e) {
			TBMCCoreAPI.SendException("An error occured while sending message to DiscordSender", e);
		}
	}

	@Override
	public void sendMessage(String[] messages) {
		sendMessage(Arrays.stream(messages).collect(Collectors.joining("\n")));
	}
}
