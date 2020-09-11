package buttondevteam.discordplugin.playerfaker;

import buttondevteam.discordplugin.mcchat.MCChatUtils;
import com.destroystokyo.paper.profile.CraftPlayerProfile;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.mockito.Mockito;

import java.lang.reflect.Modifier;
import java.util.*;

public class ServerWatcher {
	private List<Player> playerList;
	private final List<Player> fakePlayers = new ArrayList<>();
	private Server origServer;

	public void enableDisable(boolean enable) throws Exception {
		var serverField = Bukkit.class.getDeclaredField("server");
		serverField.setAccessible(true);
		if (enable) {
			var serverClass = Bukkit.getServer().getClass();
			//var mockMaker = new InlineByteBuddyMockMaker();
			//System.setProperty("net.bytebuddy.experimental", "true");
			/*try {
				var resources = cl.getResources("mockito-extensions/" + MockMaker.class.getName());
				System.out.println("Found resources: " + resources);
				Iterables.toIterable(resources).forEach(resource -> System.out.println("Resource: " + resource));
			} catch (IOException e) {
				throw new IllegalStateException("Failed to load " + MockMaker.class, e);
			}*/
			var settings = Mockito.withSettings().stubOnly()
				.defaultAnswer(invocation -> {
					var method = invocation.getMethod();
					int pc = method.getParameterCount();
					Player player = null;
					switch (method.getName()) {
						case "getPlayer":
							if (pc == 1 && method.getParameterTypes()[0] == UUID.class)
								player = MCChatUtils.LoggedInPlayers.get(invocation.<UUID>getArgument(0));
							break;
						case "getPlayerExact":
							if (pc == 1) {
								final String argument = invocation.getArgument(0);
								player = MCChatUtils.LoggedInPlayers.values().stream()
									.filter(dcp -> dcp.getName().equalsIgnoreCase(argument)).findAny().orElse(null);
							}
							break;
						case "getOnlinePlayers":
							if (playerList == null) {
								@SuppressWarnings("unchecked") var list = (List<Player>) method.invoke(origServer, invocation.getArguments());
								playerList = new AppendListView<>(list, fakePlayers);
							}
							return playerList;
						case "createProfile": //Paper's method, casts the player to a CraftPlayer
							if (pc == 2) {
								UUID uuid = invocation.getArgument(0);
								String name = invocation.getArgument(1);
								player = uuid != null ? MCChatUtils.LoggedInPlayers.get(uuid) : null;
								if (player == null && name != null)
									player = MCChatUtils.LoggedInPlayers.values().stream()
										.filter(dcp -> dcp.getName().equalsIgnoreCase(name)).findAny().orElse(null);
								if (player != null)
									return new CraftPlayerProfile(player.getUniqueId(), player.getName());
							}
							break;
					}
					if (player != null)
						return player;
					return method.invoke(origServer, invocation.getArguments());
				});
			//var mock = mockMaker.createMock(settings, MockHandlerFactory.createMockHandler(settings));
			//thread.setContextClassLoader(cl);
			var mock = Mockito.mock(serverClass, settings);
			var originalServer = serverField.get(null);
			for (var field : serverClass.getFields()) //Copy public fields, private fields aren't accessible directly anyways
				if (!Modifier.isFinal(field.getModifiers()) && !Modifier.isStatic(field.getModifiers()))
					field.set(mock, field.get(originalServer));
			serverField.set(null, mock);
			origServer = (Server) originalServer;
		} else if (origServer != null)
			serverField.set(null, origServer);
	}

	@RequiredArgsConstructor
	public static class AppendListView<T> extends AbstractSequentialList<T> {
		private final List<T> originalList;
		private final List<T> additionalList;

		@Override
		public ListIterator<T> listIterator(int i) {
			int os = originalList.size();
			return i < os ? originalList.listIterator(i) : additionalList.listIterator(i - os);
		}

		@Override
		public int size() {
			return originalList.size() + additionalList.size();
		}
	}
}
