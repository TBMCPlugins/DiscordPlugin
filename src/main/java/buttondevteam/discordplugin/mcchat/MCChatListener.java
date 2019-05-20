package buttondevteam.discordplugin.mcchat;

import buttondevteam.core.ComponentManager;
import buttondevteam.core.component.channel.Channel;
import buttondevteam.core.component.channel.ChatRoom;
import buttondevteam.discordplugin.DPUtils;
import buttondevteam.discordplugin.DiscordPlugin;
import buttondevteam.discordplugin.DiscordSender;
import buttondevteam.discordplugin.DiscordSenderBase;
import buttondevteam.discordplugin.listeners.CommandListener;
import buttondevteam.discordplugin.playerfaker.VanillaCommandListener;
import buttondevteam.lib.*;
import buttondevteam.lib.chat.ChatMessage;
import buttondevteam.lib.chat.TBMCChatAPI;
import buttondevteam.lib.player.TBMCPlayer;
import com.vdurmont.emoji.EmojiParser;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.Embed;
import discord4j.core.object.entity.*;
import discord4j.core.object.util.Snowflake;
import discord4j.core.spec.EmbedCreateSpec;
import lombok.val;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import reactor.core.publisher.Mono;

import java.awt.*;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class MCChatListener implements Listener {
	private BukkitTask sendtask;
	private LinkedBlockingQueue<AbstractMap.SimpleEntry<TBMCChatEvent, Instant>> sendevents = new LinkedBlockingQueue<>();
	private Runnable sendrunnable;
	private static Thread sendthread;
	private final MinecraftChatModule module;

	public MCChatListener(MinecraftChatModule minecraftChatModule) {
		module = minecraftChatModule;
	}

	@EventHandler // Minecraft
	public void onMCChat(TBMCChatEvent ev) {
		if (!ComponentManager.isEnabled(MinecraftChatModule.class) || ev.isCancelled()) //SafeMode: Needed so it doesn't restart after server shutdown
			return;
		sendevents.add(new AbstractMap.SimpleEntry<>(ev, Instant.now()));
		if (sendtask != null)
			return;
		sendrunnable = () -> {
			sendthread = Thread.currentThread();
			processMCToDiscord();
			if (DiscordPlugin.plugin.isEnabled()) //Don't run again if shutting down
				sendtask = Bukkit.getScheduler().runTaskAsynchronously(DiscordPlugin.plugin, sendrunnable);
		};
		sendtask = Bukkit.getScheduler().runTaskAsynchronously(DiscordPlugin.plugin, sendrunnable);
	}

	private void processMCToDiscord() {
		try {
			TBMCChatEvent e;
			Instant time;
			val se = sendevents.take(); // Wait until an element is available
			e = se.getKey();
			time = se.getValue();

			final String authorPlayer = "[" + DPUtils.sanitizeStringNoEscape(e.getChannel().DisplayName().get()) + "] " //
				+ ("Minecraft".equals(e.getOrigin()) ? "" : "[" + e.getOrigin().substring(0, 1) + "]") //
				+ (DPUtils.sanitizeStringNoEscape(ThorpeUtils.getDisplayName(e.getSender())));
			val color = e.getChannel().Color().get();
			final Consumer<EmbedCreateSpec> embed = ecs -> {
				ecs.setDescription(e.getMessage()).setColor(new Color(color.getRed(),
					color.getGreen(), color.getBlue()));
				if (e.getSender() instanceof Player)
					DPUtils.embedWithHead(ecs, authorPlayer, e.getSender().getName(),
						"https://tbmcplugins.github.io/profile.html?type=minecraft&id="
							+ ((Player) e.getSender()).getUniqueId());
				else if (e.getSender() instanceof DiscordSenderBase)
					ecs.setAuthor(authorPlayer, "https://tbmcplugins.github.io/profile.html?type=discord&id=" // TODO: Constant/method to get URLs like this
							+ ((DiscordSenderBase) e.getSender()).getUser().getId().asString(),
						((DiscordSenderBase) e.getSender()).getUser().getAvatarUrl());
				else
					DPUtils.embedWithHead(ecs, authorPlayer, e.getSender().getName(), null);
				ecs.setTimestamp(time);
			};
			final long nanoTime = System.nanoTime();
			InterruptibleConsumer<MCChatUtils.LastMsgData> doit = lastmsgdata -> {
				if (lastmsgdata.message == null
					|| !authorPlayer.equals(lastmsgdata.message.getEmbeds().get(0).getAuthor().map(Embed.Author::getName).orElse(null))
					|| lastmsgdata.time / 1000000000f < nanoTime / 1000000000f - 120
					|| !lastmsgdata.mcchannel.ID.equals(e.getChannel().ID)) {
					lastmsgdata.message = lastmsgdata.channel.createEmbed(embed).block();
					lastmsgdata.time = nanoTime;
					lastmsgdata.mcchannel = e.getChannel();
					lastmsgdata.content = e.getMessage();
				} else {
					lastmsgdata.content = lastmsgdata.content + "\n"
						+ e.getMessage(); // The message object doesn't get updated
					lastmsgdata.message.edit(mes -> mes.setEmbed(embed.andThen(ecs ->
						ecs.setDescription(lastmsgdata.content)))).block();
				}
			};
			// Checks if the given channel is different than where the message was sent from
			// Or if it was from MC
			Predicate<Snowflake> isdifferentchannel = id -> !(e.getSender() instanceof DiscordSenderBase)
				|| ((DiscordSenderBase) e.getSender()).getChannel().getId().asLong() != id.asLong();

			if (e.getChannel().isGlobal()
				&& (e.isFromCommand() || isdifferentchannel.test(module.chatChannel().get())))
				doit.accept(MCChatUtils.lastmsgdata == null
					? MCChatUtils.lastmsgdata = new MCChatUtils.LastMsgData(module.chatChannelMono().block(), null)
					: MCChatUtils.lastmsgdata);

			for (MCChatUtils.LastMsgData data : MCChatPrivate.lastmsgPerUser) {
				if ((e.isFromCommand() || isdifferentchannel.test(data.channel.getId()))
					&& e.shouldSendTo(MCChatUtils.getSender(data.channel.getId(), data.user)))
					doit.accept(data);
			}

			val iterator = MCChatCustom.lastmsgCustom.iterator();
			while (iterator.hasNext()) {
				val lmd = iterator.next();
				if ((e.isFromCommand() || isdifferentchannel.test(lmd.channel.getId())) //Test if msg is from Discord
					&& e.getChannel().ID.equals(lmd.mcchannel.ID) //If it's from a command, the command msg has been deleted, so we need to send it
					&& e.getGroupID().equals(lmd.groupID)) { //Check if this is the group we want to test - #58
					if (e.shouldSendTo(lmd.dcp)) //Check original user's permissions
						doit.accept(lmd);
					else {
						iterator.remove(); //If the user no longer has permission, remove the connection
						lmd.channel.createMessage("The user no longer has permission to view the channel, connection removed.").subscribe();
					}
				}
			}
		} catch (InterruptedException ex) { //Stop if interrupted anywhere
			sendtask.cancel();
			sendtask = null;
		} catch (Exception ex) {
			TBMCCoreAPI.SendException("Error while sending message to Discord!", ex);
		}
	}

	@EventHandler
	public void onChatPreprocess(TBMCChatPreprocessEvent event) {
		int start = -1;
		while ((start = event.getMessage().indexOf('@', start + 1)) != -1) {
			int mid = event.getMessage().indexOf('#', start + 1);
			if (mid == -1)
				return;
			int end_ = event.getMessage().indexOf(' ', mid + 1);
			if (end_ == -1)
				end_ = event.getMessage().length();
			final int end = end_;
			final int startF = start;
			val user = DiscordPlugin.dc.getUsers().filter(u -> u.getUsername().equals(event.getMessage().substring(startF + 1, mid)))
				.filter(u -> u.getDiscriminator().equals(event.getMessage().substring(mid + 1, end))).blockFirst();
			if (user != null) //TODO: Nicknames
				event.setMessage(event.getMessage().substring(0, startF) + "@" + user.getUsername()
					+ (event.getMessage().length() > end ? event.getMessage().substring(end) : "")); // TODO: Add formatting
			start = end; // Skip any @s inside the mention
		}
	}

	// ......................DiscordSender....DiscordConnectedPlayer.DiscordPlayerSender
	// Offline public chat......x............................................
	// Online public chat.......x...........................................x
	// Offline private chat.....x.......................x....................
	// Online private chat......x.......................x...................x
	// If online and enabling private chat, don't login
	// If leaving the server and private chat is enabled (has ConnectedPlayer), call login in a task on lowest priority
	// If private chat is enabled and joining the server, logout the fake player on highest priority
	// If online and disabling private chat, don't logout
	// The maps may not contain the senders for UnconnectedSenders

	/**
	 * Stop the listener. Any calls to onMCChat will restart it as long as we're not in safe mode.
	 *
	 * @param wait Wait 5 seconds for the threads to stop
	 */
	public static void stop(boolean wait) {
		if (sendthread != null) sendthread.interrupt();
		if (recthread != null) recthread.interrupt();
		try {
			if (sendthread != null) {
				sendthread.interrupt();
				if (wait)
					sendthread.join(5000);
			}
			if (recthread != null) {
				recthread.interrupt();
				if (wait)
					recthread.join(5000);
			}
			MCChatUtils.lastmsgdata = null;
			MCChatPrivate.lastmsgPerUser.clear();
			MCChatCustom.lastmsgCustom.clear();
			MCChatUtils.lastmsgfromd.clear();
			MCChatUtils.ConnectedSenders.clear();
			MCChatUtils.UnconnectedSenders.clear();
			recthread = sendthread = null;
		} catch (InterruptedException e) {
			e.printStackTrace(); //This thread shouldn't be interrupted
		}
	}

	private BukkitTask rectask;
	private LinkedBlockingQueue<MessageCreateEvent> recevents = new LinkedBlockingQueue<>();
	private Runnable recrun;
	private static Thread recthread;

	// Discord
	public Mono<Boolean> handleDiscord(MessageCreateEvent ev) {
		val ret = Mono.just(true);
		if (!ComponentManager.isEnabled(MinecraftChatModule.class))
			return ret;
		System.out.println("Chat event");
		val author = ev.getMessage().getAuthor();
		final boolean hasCustomChat = MCChatCustom.hasCustomChat(ev.getMessage().getChannelId());
		return ev.getMessage().getChannel().filter(channel -> {
			System.out.println("Filter 1");
			return !(ev.getMessage().getChannelId().asLong() != module.chatChannel().get().asLong()
				&& !(channel instanceof PrivateChannel
				&& author.map(u -> MCChatPrivate.isMinecraftChatEnabled(u.getId().asString())).orElse(false)
				&& !hasCustomChat)); //Chat isn't enabled on this channel
		}).filter(channel -> {
			System.out.println("Filter 2");
			return !(channel instanceof PrivateChannel //Only in private chat
				&& ev.getMessage().getContent().isPresent()
				&& ev.getMessage().getContent().get().length() < "/mcchat<>".length()
				&& ev.getMessage().getContent().get().replace("/", "")
				.equalsIgnoreCase("mcchat")); //Either mcchat or /mcchat
			//Allow disabling the chat if needed
		}).filterWhen(channel -> CommandListener.runCommand(ev.getMessage(), channel, true))
			//Allow running commands in chat channels
			.filter(channel -> {
				MCChatUtils.resetLastMessage(channel);
				recevents.add(ev);
				System.out.println("Message event added");
				if (rectask != null)
					return true;
				recrun = () -> { //Don't return in a while loop next time
					recthread = Thread.currentThread();
					processDiscordToMC();
					if (DiscordPlugin.plugin.isEnabled()) //Don't run again if shutting down
						rectask = Bukkit.getScheduler().runTaskAsynchronously(DiscordPlugin.plugin, recrun); //Continue message processing
				};
				rectask = Bukkit.getScheduler().runTaskAsynchronously(DiscordPlugin.plugin, recrun); //Start message processing
				return true;
			}).map(b -> false).defaultIfEmpty(true);
	}

	private void processDiscordToMC() {
		MessageCreateEvent event;
		try {
			event = recevents.take();
		} catch (InterruptedException e1) {
			rectask.cancel();
			return;
		}
		System.out.println("Processing...");
		val sender = event.getMessage().getAuthor().orElse(null);
		String dmessage = event.getMessage().getContent().orElse("");
		try {
			final DiscordSenderBase dsender = MCChatUtils.getSender(event.getMessage().getChannelId(), sender);
			val user = dsender.getChromaUser();

			System.out.println("Mentions start");
			for (User u : event.getMessage().getUserMentions().toIterable()) { //TODO: Role mentions
				dmessage = dmessage.replace(u.getMention(), "@" + u.getUsername()); // TODO: IG Formatting
				val m = u.asMember(DiscordPlugin.mainServer.getId()).block();
				if (m != null) {
					final String nick = m.getDisplayName();
					dmessage = dmessage.replace(m.getNicknameMention(), "@" + nick);
				}
			}
			for (GuildChannel ch : event.getGuild().flux().flatMap(Guild::getChannels).toIterable()) {
				dmessage = dmessage.replace(ch.getMention(), "#" + ch.getName()); // TODO: IG Formatting
			}
			System.out.println("Mentions end");

			dmessage = EmojiParser.parseToAliases(dmessage, EmojiParser.FitzpatrickAction.PARSE); //Converts emoji to text- TODO: Add option to disable (resource pack?)
			dmessage = dmessage.replaceAll(":(\\S+)\\|type_(?:(\\d)|(1)_2):", ":$1::skin-tone-$2:"); //Convert to Discord's format so it still shows up

			Function<String, String> getChatMessage = msg -> //
				msg + (event.getMessage().getAttachments().size() > 0 ? "\n" + event.getMessage()
					.getAttachments().stream().map(Attachment::getUrl).collect(Collectors.joining("\n"))
					: "");

			MCChatCustom.CustomLMD clmd = MCChatCustom.getCustomChat(event.getMessage().getChannelId());

			boolean react = false;

			System.out.println("Getting channel...");
			val sendChannel = event.getMessage().getChannel().block();
			System.out.println("Got channel");
			boolean isPrivate = sendChannel instanceof PrivateChannel;
			if (dmessage.startsWith("/")) { // Ingame command
				if (!isPrivate)
					event.getMessage().delete().subscribe();
				final String cmd = dmessage.substring(1);
				final String cmdlowercased = cmd.toLowerCase();
				if (dsender instanceof DiscordSender && module.whitelistedCommands().get().stream()
					.noneMatch(s -> cmdlowercased.equals(s) || cmdlowercased.startsWith(s + " "))) {
					// Command not whitelisted
					dsender.sendMessage("Sorry, you can only access these commands:\n"
						+ module.whitelistedCommands().get().stream().map(uc -> "/" + uc)
						.collect(Collectors.joining(", "))
						+ (user.getConnectedID(TBMCPlayer.class) == null
						? "\nTo access your commands, first please connect your accounts, using /connect in "
						+ DPUtils.botmention()
						+ "\nThen y"
						: "\nY")
						+ "ou can access all of your regular commands (even offline) in private chat: DM me `mcchat`!");
					return;
				}
				val ev = new TBMCCommandPreprocessEvent(dsender, dmessage);
				Bukkit.getPluginManager().callEvent(ev);
				if (ev.isCancelled())
					return;
				int spi = cmdlowercased.indexOf(' ');
				final String topcmd = spi == -1 ? cmdlowercased : cmdlowercased.substring(0, spi);
				Optional<Channel> ch = Channel.getChannels()
					.filter(c -> c.ID.equalsIgnoreCase(topcmd)
						|| (c.IDs().get().length > 0
						&& Arrays.stream(c.IDs().get()).anyMatch(id -> id.equalsIgnoreCase(topcmd)))).findAny();
				if (!ch.isPresent()) //TODO: What if talking in the public chat while we have it on a different one
					Bukkit.getScheduler().runTask(DiscordPlugin.plugin, //Commands need to be run sync
						() -> { //TODO: Better handling...
							val channel = user.channel();
							val chtmp = channel.get();
							if (clmd != null) {
								channel.set(clmd.mcchannel); //Hack to send command in the channel
							} //TODO: Permcheck isn't implemented for commands
							VanillaCommandListener.runBukkitOrVanillaCommand(dsender, cmd);
							Bukkit.getLogger().info(dsender.getName() + " issued command from Discord: /" + cmdlowercased);
							if (clmd != null)
								channel.set(chtmp);
						});
				else {
					Channel chc = ch.get();
					if (!chc.isGlobal() && !isPrivate)
						dsender.sendMessage(
							"You can only talk in a public chat here. DM `mcchat` to enable private chat to talk in the other channels.");
					else {
						if (spi == -1) // Switch channels
						{
							val channel = dsender.getChromaUser().channel();
							val oldch = channel.get();
							if (oldch instanceof ChatRoom)
								((ChatRoom) oldch).leaveRoom(dsender);
							if (!oldch.ID.equals(chc.ID)) {
								channel.set(chc);
								if (chc instanceof ChatRoom)
									((ChatRoom) chc).joinRoom(dsender);
							} else
								channel.set(Channel.GlobalChat);
							dsender.sendMessage("You're now talking in: "
								+ DPUtils.sanitizeString(channel.get().DisplayName().get()));
						} else { // Send single message
							final String msg = cmd.substring(spi + 1);
							val cmb = ChatMessage.builder(dsender, user, getChatMessage.apply(msg)).fromCommand(true);
							if (clmd == null)
								TBMCChatAPI.SendChatMessage(cmb.build(), chc);
							else
								TBMCChatAPI.SendChatMessage(cmb.permCheck(clmd.dcp).build(), chc);
							react = true;
						}
					}
				}
			} else {// Not a command
				System.out.println("Not a command");
				if (dmessage.length() == 0 && event.getMessage().getAttachments().size() == 0
					&& !isPrivate && event.getMessage().getType() == Message.Type.CHANNEL_PINNED_MESSAGE) {
					val rtr = clmd != null ? clmd.mcchannel.getRTR(clmd.dcp)
						: dsender.getChromaUser().channel().get().getRTR(dsender);
					TBMCChatAPI.SendSystemMessage(clmd != null ? clmd.mcchannel : dsender.getChromaUser().channel().get(), rtr,
						(dsender instanceof Player ? ((Player) dsender).getDisplayName()
							: dsender.getName()) + " pinned a message on Discord.", TBMCSystemChatEvent.BroadcastTarget.ALL);
				} else {
					val cmb = ChatMessage.builder(dsender, user, getChatMessage.apply(dmessage)).fromCommand(false);
					System.out.println("Message created");
					if (clmd != null)
						TBMCChatAPI.SendChatMessage(cmb.permCheck(clmd.dcp).build(), clmd.mcchannel);
					else
						TBMCChatAPI.SendChatMessage(cmb.build());
					react = true;
					System.out.println("Message sent");
				}
			}
			if (react) {
				try {
					val lmfd = MCChatUtils.lastmsgfromd.get(event.getMessage().getChannelId().asLong());
					if (lmfd != null) {
						lmfd.removeSelfReaction(DiscordPlugin.DELIVERED_REACTION).subscribe(); // Remove it no matter what, we know it's there 99.99% of the time
					}
				} catch (Exception e) {
					TBMCCoreAPI.SendException("An error occured while removing reactions from chat!", e);
				}
				MCChatUtils.lastmsgfromd.put(event.getMessage().getChannelId().asLong(), event.getMessage());
				event.getMessage().addReaction(DiscordPlugin.DELIVERED_REACTION).subscribe();
			}
		} catch (Exception e) {
			TBMCCoreAPI.SendException("An error occured while handling message \"" + dmessage + "\"!", e);
		}
	}

	@FunctionalInterface
	private interface InterruptibleConsumer<T> {
		void accept(T value) throws TimeoutException, InterruptedException;
	}
}
