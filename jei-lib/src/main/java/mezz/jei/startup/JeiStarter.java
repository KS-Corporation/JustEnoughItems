package mezz.jei.startup;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.common.progress.ProgressBar;
import net.minecraftforge.fml.common.progress.StartupProgressManager;
import net.minecraft.util.NonNullList;

import com.google.common.base.Stopwatch;
import mezz.jei.Internal;
import mezz.jei.api.IIngredientFilter;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.gui.IAdvancedGuiHandler;
import mezz.jei.api.gui.IGhostIngredientHandler;
import mezz.jei.api.gui.IGlobalGuiHandler;
import mezz.jei.api.gui.IGuiScreenHandler;
import mezz.jei.bookmarks.BookmarkList;
import mezz.jei.config.ClientConfig;
import mezz.jei.config.IHideModeConfig;
import mezz.jei.gui.GuiEventHandler;
import mezz.jei.gui.GuiScreenHelper;
import mezz.jei.gui.ingredients.IIngredientListElement;
import mezz.jei.gui.overlay.GridAlignment;
import mezz.jei.gui.overlay.IngredientGridWithNavigation;
import mezz.jei.gui.overlay.IngredientListOverlay;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import mezz.jei.gui.overlay.bookmarks.LeftAreaDispatcher;
import mezz.jei.gui.recipes.RecipesGui;
import mezz.jei.ingredients.IngredientBlacklistInternal;
import mezz.jei.ingredients.IngredientFilter;
import mezz.jei.ingredients.IngredientFilterApi;
import mezz.jei.ingredients.IngredientListElementFactory;
import mezz.jei.ingredients.IngredientRegistry;
import mezz.jei.input.InputHandler;
import mezz.jei.plugins.vanilla.VanillaPlugin;
import mezz.jei.recipes.RecipeRegistry;
import mezz.jei.runtime.JeiHelpers;
import mezz.jei.runtime.JeiRuntime;
import mezz.jei.runtime.SubtypeRegistry;
import mezz.jei.util.ErrorUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class JeiStarter {
	private static final Logger LOGGER = LogManager.getLogger();
	
	private boolean started;

	public void start(List<IModPlugin> plugins, ClientConfig config, IHideModeConfig hideModeConfig) {
		ErrorUtil.checkNotEmpty(plugins, "plugins");
		LoggedTimer totalTime = new LoggedTimer();
		totalTime.start("Starting JEI");

		SubtypeRegistry subtypeRegistry = new SubtypeRegistry();

		registerItemSubtypes(plugins, subtypeRegistry);

		StackHelper stackHelper = new StackHelper(subtypeRegistry);
		stackHelper.enableUidCache();
		Internal.setStackHelper(stackHelper);

		IngredientBlacklistInternal blacklist = new IngredientBlacklistInternal();
		ModIngredientRegistration modIngredientRegistry = registerIngredients(plugins);
		IngredientRegistry ingredientRegistry = modIngredientRegistry.createIngredientRegistry(ForgeModIdHelper.getInstance(), blacklist, config.isDebugModeEnabled());
		Internal.setIngredientRegistry(ingredientRegistry);

		JeiHelpers jeiHelpers = new JeiHelpers(ingredientRegistry, blacklist, stackHelper, hideModeConfig);
		Internal.setHelpers(jeiHelpers);

		ModRegistry modRegistry = new ModRegistry(jeiHelpers, ingredientRegistry);

		LoggedTimer timer = new LoggedTimer();
		timer.start("Registering recipe categories");
		registerCategories(plugins, modRegistry);
		timer.stop();

		timer.start("Registering mod plugins");
		registerPlugins(plugins, modRegistry);
		timer.stop();

		timer.start("Building recipe registry");
		RecipeRegistry recipeRegistry = modRegistry.createRecipeRegistry(ingredientRegistry);
		timer.stop();

		timer.start("Building ingredient list");
		NonNullList<IIngredientListElement> ingredientList = IngredientListElementFactory.createBaseList(ingredientRegistry, ForgeModIdHelper.getInstance());
		timer.stop();

		timer.start("Building ingredient filter");
		IngredientFilter ingredientFilter = new IngredientFilter(blacklist, config, hideModeConfig);
		ingredientFilter.addIngredients(ingredientList);
		Internal.setIngredientFilter(ingredientFilter);
		timer.stop();

		timer.start("Building bookmarks");
		BookmarkList bookmarkList = new BookmarkList(ingredientRegistry);
		bookmarkList.loadBookmarks();
		timer.stop();

		timer.start("Building runtime");
		List<IAdvancedGuiHandler<?>> advancedGuiHandlers = modRegistry.getAdvancedGuiHandlers();
		List<IGlobalGuiHandler> globalGuiHandlers = modRegistry.getGlobalGuiHandlers();
		Map<Class, IGuiScreenHandler> guiScreenHandlers = modRegistry.getGuiScreenHandlers();
		Map<Class, IGhostIngredientHandler> ghostIngredientHandlers = modRegistry.getGhostIngredientHandlers();
		GuiScreenHelper guiScreenHelper = new GuiScreenHelper(ingredientRegistry, globalGuiHandlers, advancedGuiHandlers, ghostIngredientHandlers, guiScreenHandlers);
		IngredientGridWithNavigation ingredientListGrid = new IngredientGridWithNavigation(ingredientFilter, config, guiScreenHelper, hideModeConfig, GridAlignment.LEFT);
		IngredientListOverlay ingredientListOverlay = new IngredientListOverlay(ingredientFilter, config, ingredientRegistry, guiScreenHelper, ingredientListGrid);

		IngredientGridWithNavigation bookmarkListGrid = new IngredientGridWithNavigation(ingredientFilter, () -> "", guiScreenHelper, hideModeConfig, GridAlignment.RIGHT);
		BookmarkOverlay bookmarkOverlay = new BookmarkOverlay(bookmarkList, jeiHelpers.getGuiHelper(), bookmarkListGrid);
		RecipesGui recipesGui = new RecipesGui(recipeRegistry, ingredientRegistry);
		IIngredientFilter ingredientFilterApi = new IngredientFilterApi(ingredientFilter);
		JeiRuntime jeiRuntime = new JeiRuntime(recipeRegistry, ingredientListOverlay, recipesGui, ingredientFilterApi);
		Internal.setRuntime(jeiRuntime);
		timer.stop();

		stackHelper.disableUidCache();

		sendRuntime(plugins, jeiRuntime);

		LeftAreaDispatcher leftAreaDispatcher = new LeftAreaDispatcher(guiScreenHelper);
		leftAreaDispatcher.addContent(bookmarkOverlay);

		GuiEventHandler guiEventHandler = new GuiEventHandler(guiScreenHelper, leftAreaDispatcher, ingredientListOverlay, recipeRegistry);
		Internal.setGuiEventHandler(guiEventHandler);
		InputHandler inputHandler = new InputHandler(jeiRuntime, ingredientFilter, ingredientRegistry, ingredientListOverlay, hideModeConfig, guiScreenHelper, leftAreaDispatcher, bookmarkList);
		Internal.setInputHandler(inputHandler);

		config.checkForModNameFormatOverride();

		started = true;
		totalTime.stop();
	}

	public boolean hasStarted() {
		return started;
	}

	private static void registerItemSubtypes(List<IModPlugin> plugins, SubtypeRegistry subtypeRegistry) {
		try (ProgressBar progressBar = StartupProgressManager.start("Registering item subtypes", plugins.size())) {
			Iterator<IModPlugin> iterator = plugins.iterator();
			while (iterator.hasNext()) {
				IModPlugin plugin = iterator.next();
				try {
					progressBar.step(plugin.getClass().getName());
					plugin.registerItemSubtypes(subtypeRegistry);
				} catch (RuntimeException | LinkageError e) {
					LOGGER.error("Failed to register item subtypes for mod plugin: {}", plugin.getClass(), e);
					iterator.remove();
				}
			}
		}
	}

	private static ModIngredientRegistration registerIngredients(List<IModPlugin> plugins) {
		ModIngredientRegistration modIngredientRegistry = new ModIngredientRegistration();
		try (ProgressBar progressBar = StartupProgressManager.start("Registering ingredients", plugins.size())) {
			Iterator<IModPlugin> iterator = plugins.iterator();
			while (iterator.hasNext()) {
				IModPlugin plugin = iterator.next();
				try {
					progressBar.step(plugin.getClass().getName());
					plugin.registerIngredients(modIngredientRegistry);
				} catch (RuntimeException | LinkageError e) {
					if (plugin instanceof VanillaPlugin) {
						throw e;
					} else {
						LOGGER.error("Failed to register Ingredients for mod plugin: {}", plugin.getClass(), e);
						iterator.remove();
					}
				}
			}
		}
		return modIngredientRegistry;
	}

	private static void registerCategories(List<IModPlugin> plugins, ModRegistry modRegistry) {
		try (ProgressBar progressBar = StartupProgressManager.start("Registering categories", plugins.size())) {
			Iterator<IModPlugin> iterator = plugins.iterator();
			while (iterator.hasNext()) {
				IModPlugin plugin = iterator.next();
				try {
					progressBar.step(plugin.getClass().getName());
					long start_time = System.currentTimeMillis();
					LOGGER.debug("Registering categories: {} ...", plugin.getClass().getName());
					plugin.registerCategories(modRegistry);
					long timeElapsedMs = System.currentTimeMillis() - start_time;
					LOGGER.debug("Registered  categories: {} in {} ms", plugin.getClass().getName(), timeElapsedMs);
				} catch (AbstractMethodError ignored) {
					// legacy plugins do not implement registerCategories
				} catch (RuntimeException | LinkageError e) {
					LOGGER.error("Failed to register mod categories: {}", plugin.getClass(), e);
					iterator.remove();
				}
			}
		}
	}

	private static void registerPlugins(List<IModPlugin> plugins, ModRegistry modRegistry) {
		try (ProgressBar progressBar = StartupProgressManager.start("Registering plugins", plugins.size())) {
			Iterator<IModPlugin> iterator = plugins.iterator();
			while (iterator.hasNext()) {
				IModPlugin plugin = iterator.next();
				try {
					progressBar.step(plugin.getClass().getName());
					long start_time = System.currentTimeMillis();
					LOGGER.debug("Registering plugin: {} ...", plugin.getClass().getName());
					plugin.register(modRegistry);
					long timeElapsedMs = System.currentTimeMillis() - start_time;
					LOGGER.debug("Registered  plugin: {} in {} ms", plugin.getClass().getName(), timeElapsedMs);
				} catch (RuntimeException | LinkageError e) {
					LOGGER.error("Failed to register mod plugin: {}", plugin.getClass(), e);
					iterator.remove();
				}
			}
		}
	}

	private static void sendRuntime(List<IModPlugin> plugins, IJeiRuntime jeiRuntime) {
		try (ProgressBar progressBar = StartupProgressManager.start("Sending Runtime", plugins.size())) {
			Iterator<IModPlugin> iterator = plugins.iterator();
			while (iterator.hasNext()) {
				IModPlugin plugin = iterator.next();
				try {
					progressBar.step(plugin.getClass().getName());
					long start_time = System.currentTimeMillis();
					LOGGER.debug("Sending runtime to plugin: {} ...", plugin.getClass().getName());
					plugin.onRuntimeAvailable(jeiRuntime);
					long timeElapsedMs = System.currentTimeMillis() - start_time;
					if (timeElapsedMs > 100) {
						LOGGER.warn("Sending runtime to plugin: {} took {} ms", plugin.getClass().getName(), timeElapsedMs);
					}
				} catch (RuntimeException | LinkageError e) {
					LOGGER.error("Sending runtime to plugin failed: {}", plugin.getClass(), e);
					iterator.remove();
				}
			}
		}
	}

	private static class LoggedTimer {
		private final Stopwatch stopWatch = Stopwatch.createUnstarted();
		private String message = "";

		public void start(String message) {
			this.message = message;
			LOGGER.info("{}...", message);
			stopWatch.reset();
			stopWatch.start();
		}

		public void stop() {
			stopWatch.stop();
			LOGGER.info("{} took {}", message, stopWatch);
		}
	}
}
