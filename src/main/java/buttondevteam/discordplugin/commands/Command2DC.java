package buttondevteam.discordplugin.commands;

import buttondevteam.discordplugin.DiscordPlugin;
import buttondevteam.lib.chat.Command2;

import java.lang.reflect.Method;

public class Command2DC extends Command2<ICommand2DC, Command2DCSender> {
	@Override
	public void registerCommand(ICommand2DC command) {
		super.registerCommand(command, DiscordPlugin.getPrefix()); //Needs to be configurable for the helps
	}

	@Override
	public boolean hasPermission(Command2DCSender sender, ICommand2DC command, Method method) {
		//return !command.isModOnly() || sender.getMessage().getAuthor().hasRole(DiscordPlugin.plugin.modRole().get()); //TODO: modRole may be null; more customisable way?
		return true;
	}
}
