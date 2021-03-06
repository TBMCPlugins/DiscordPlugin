package buttondevteam.discordplugin;

import buttondevteam.discordplugin.mcchat.MCChatPrivate;
import buttondevteam.lib.player.ChromaGamerBase;
import buttondevteam.lib.player.UserClass;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.MessageChannel;

@UserClass(foldername = "discord")
public class DiscordPlayer extends ChromaGamerBase {
	private String did;
	// private @Getter @Setter boolean minecraftChatEnabled;

	public DiscordPlayer() {
	}

	public String getDiscordID() {
		if (did == null)
			did = getFileName();
		return did;
	}

	/**
	 * Returns true if player has the private Minecraft chat enabled. For setting the value, see
	 * {@link MCChatPrivate#privateMCChat(MessageChannel, boolean, User, DiscordPlayer)}
	 */
	public boolean isMinecraftChatEnabled() {
		return MCChatPrivate.isMinecraftChatEnabled(this);
	}
}
