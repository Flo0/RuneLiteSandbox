package com.gestankbratwurst;

import com.gestankbratwurst.autofight.AutoFighter;
import com.gestankbratwurst.autofight.SandcrabFighter;
import com.gestankbratwurst.autoharvester.AutoMiner;
import com.gestankbratwurst.autoharvester.AutoWoodcutter;
import com.gestankbratwurst.autoharvester.DwarvenAutoCoal;
import com.gestankbratwurst.autoharvester.DwarvenAutoIron;
import com.gestankbratwurst.autoharvester.GemMiner;
import com.gestankbratwurst.autoharvester.PlankGatherer;
import com.gestankbratwurst.mousemovement.MouseAgent;
import com.gestankbratwurst.simplewalk.PathTravel;
import com.gestankbratwurst.simplewalk.PathTravelOverlay;
import com.gestankbratwurst.simplewalk.SimpleWalker;
import com.gestankbratwurst.utils.EnvironmentUtils;
import com.gestankbratwurst.utils.InventoryUtils;
import com.gestankbratwurst.utils.Rock;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.InventoryID;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuEntry;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Slf4j
@PluginDescriptor(
        name = "Flos RuneLiteAddons"
)
public class RuneLiteAddons extends Plugin {

  @Getter
  private final Map<WorldPoint, AvailableOre> ores = new HashMap<>();
  private final LinkedList<FutureTickTask> tasks = new LinkedList<>();
  private final List<FutureTickTask> pendingAddTasks = new ArrayList<>();
  private final ConcurrentLinkedQueue<CompletionTask<?>> completionTasks = new ConcurrentLinkedQueue<>();
  private final Map<Class<?>, Deque<ConditionedFutureFuture<?>>> eventFutureQueue = new HashMap<>();
  private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();

  @Getter
  @Inject
  private Client client;

  @Getter
  @Inject
  private RuneLiteAdddonsConfig config;
  //@Inject
  //private InventoryGridOverlay inventoryOverlay;

  @Inject
  private OreDetectOverlay oreOverlay;

  @Inject
  private OverlayManager overlayManager;

  @Getter
  private MouseAgent mouseAgent;

  @Getter
  private AutoFighter autoFighter;

  @Getter
  private AutoWoodcutter autoWoodcutter;

  @Getter
  private AutoMiner autoMiner;

  @Getter
  private SimpleWalker simpleWalker;

  @Getter
  private PathTravel pathTravel;

  @Getter
  private SandcrabFighter sandcrabFighter;

  @Getter
  private PlankGatherer plankGatherer;

  @Getter
  private DwarvenAutoIron dwarvenAutoIron;

  @Getter
  private DwarvenAutoCoal dwarvenAutoCoal;

  @Getter
  private GemMiner gemMiner;

  private void initAfterLogin() {
    if (mouseAgent == null) {
      mouseAgent = new MouseAgent(this);
    }
    if (autoFighter == null) {
      autoFighter = new AutoFighter(this);
    }
    if (sandcrabFighter == null) {
      sandcrabFighter = new SandcrabFighter(this);
    }
    if (autoWoodcutter == null) {
      autoWoodcutter = new AutoWoodcutter(this);
    }
    if (simpleWalker == null) {
      simpleWalker = new SimpleWalker(this);
    }
    if (autoMiner == null) {
      autoMiner = new AutoMiner(this);
    }
    if (plankGatherer == null) {
      plankGatherer = new PlankGatherer(this);
    }
    if (dwarvenAutoIron == null) {
      dwarvenAutoIron = new DwarvenAutoIron(this);
    }
    if (dwarvenAutoCoal == null) {
      dwarvenAutoCoal = new DwarvenAutoCoal(this);
    }
    if (gemMiner == null) {
      gemMiner = new GemMiner(this);
    }
    // EnvironmentUtils.startPickupLoop(this);
  }

  public <T> CompletableFuture<T> waitForEvent(Class<T> eventClass, Predicate<T> condition, long timeout) {
    CompletableFuture<T> future = new CompletableFuture<>();
    ConditionedFutureFuture<T> conditionedFutureFuture = new ConditionedFutureFuture<>(condition, future);
    eventFutureQueue.computeIfAbsent(eventClass, key -> new LinkedList<>()).add(conditionedFutureFuture);
    timeoutExecutor.schedule(conditionedFutureFuture::completeTimeOut, timeout, TimeUnit.MILLISECONDS);
    return future;
  }

  public <T> CompletableFuture<T> supplySync(Supplier<T> supplier) {
    CompletableFuture<T> future = new CompletableFuture<>();
    completionTasks.add(new CompletionTask<>(future, supplier));
    return future;
  }

  public CompletableFuture<Void> runSync(Runnable runnable) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    completionTasks.add(new CompletionTask<>(future, () -> {
      runnable.run();
      return null;
    }));
    return future;
  }

  @Override
  protected void startUp() {
    pathTravel = new PathTravel(this);
    // overlayManager.add(inventoryOverlay);
    // overlayManager.add(oreOverlay);
    overlayManager.add(new PathTravelOverlay(this));
    log.info("> RuneLiteSandbox started!");
  }

  @Override
  protected void shutDown() {
    // overlayManager.remove(oreOverlay);
    // overlayManager.remove(inventoryOverlay);
    log.info("> RuneLiteSandbox stopped!");
  }

  public void addTask(int delay, Runnable action) {
    pendingAddTasks.add(new FutureTickTask(delay, action));
  }

  @SuppressWarnings("unchecked")
  private <T> void checkConditionedFutureQueue(T event) {
    LinkedList<ConditionedFutureFuture<?>> queue = (LinkedList<ConditionedFutureFuture<?>>) eventFutureQueue.computeIfAbsent(event.getClass(), key -> new LinkedList<>());
    queue.removeIf(condition -> ((ConditionedFutureFuture<T>) condition).checkCompletion(event));
  }

  @Subscribe
  public void onWidgetLoaded(WidgetLoaded event) {
    checkConditionedFutureQueue(event);
  }

  @Subscribe
  public void onItemContainerChanged(ItemContainerChanged event) {
    EnvironmentUtils.releasePickup();
    checkConditionedFutureQueue(event);
    if (event.getItemContainer().getId() == InventoryID.INVENTORY.getId() && autoWoodcutter != null) {
      autoWoodcutter.notifyInventoryUpdate();
    }
    if (event.getItemContainer().getId() == InventoryID.INVENTORY.getId() && autoMiner != null) {
      autoMiner.notifyInventoryUpdate();
    }
  }

  @Subscribe
  public void onAnimationChanged(AnimationChanged event) {
    checkConditionedFutureQueue(event);
    if (autoWoodcutter != null && event.getActor().equals(client.getLocalPlayer())) {
      autoWoodcutter.notifyActionChange(event);
    }
    if (autoMiner != null && event.getActor().equals(client.getLocalPlayer())) {
      autoMiner.notifyActionChange(event);
    }
  }

  @Subscribe
  public void onClientTick(ClientTick tick) {
    int left = completionTasks.size();
    while (!completionTasks.isEmpty() && left > 0) {
      CompletionTask<?> task = completionTasks.poll();
      --left;
      if (task == null) {
        continue;
      }
      task.completeOnCurrentThread();
    }

    if (client.isKeyPressed(KeyCode.KC_X)) {
      autoFighter.stop();
      sandcrabFighter.stop();
      autoWoodcutter.stop();
      autoMiner.stop();
      simpleWalker.stop();
      pathTravel.stop();
      plankGatherer.stop();
      dwarvenAutoIron.stop();
      dwarvenAutoCoal.stop();
      gemMiner.stop();
    }

    if (client.isKeyPressed(KeyCode.KC_SHIFT) && client.isKeyPressed(KeyCode.KC_CONTROL) && client.isKeyPressed(KeyCode.KC_S)) {
      sandcrabFighter.start();
    }

    if (client.isKeyPressed(KeyCode.KC_SHIFT) && client.isKeyPressed(KeyCode.KC_CONTROL) && client.isKeyPressed(KeyCode.KC_P)) {
      plankGatherer.start();
    }

    if (client.isKeyPressed(KeyCode.KC_SHIFT) && client.isKeyPressed(KeyCode.KC_CONTROL) && client.isKeyPressed(KeyCode.KC_I)) {
      dwarvenAutoIron.start();
    }

    if (client.isKeyPressed(KeyCode.KC_SHIFT) && client.isKeyPressed(KeyCode.KC_CONTROL) && client.isKeyPressed(KeyCode.KC_C)) {
      dwarvenAutoCoal.start();
    }

    if (client.isKeyPressed(KeyCode.KC_SHIFT) && client.isKeyPressed(KeyCode.KC_CONTROL) && client.isKeyPressed(KeyCode.KC_G)) {
      gemMiner.start();
    }
  }

  @Subscribe
  public void onGameTick(GameTick tick) {
    tasks.addAll(pendingAddTasks);
    pendingAddTasks.clear();
    if (simpleWalker != null) {
      simpleWalker.nextTick();
    }
    if (pathTravel != null) {
      pathTravel.nextTick();
    }
    if (autoMiner != null) {
      autoMiner.nextTick();
    }
    if (autoWoodcutter != null) {
      autoWoodcutter.nextTick();
    }
    if (autoFighter != null) {
      autoFighter.nextTick();
    }
    if (sandcrabFighter != null) {
      sandcrabFighter.nextTick();
    }
    if (plankGatherer != null) {
      plankGatherer.nextTick();
    }
    tasks.removeIf(task -> {
      if (task == null) {
        return true;
      }
      return task.tick();
    });
  }



  @Subscribe
  public void onActorDeath(ActorDeath actorDeath) {
    autoFighter.onActorDeath(actorDeath.getActor());
  }

  @Subscribe
  public void onGameStateChanged(GameStateChanged gameStateChanged) {
    if (gameStateChanged.getGameState() == GameState.LOGGED_IN) {

      addTask(1, this::initAfterLogin);

      addTask(2, () -> {
        for (Tile[][] plane : client.getScene().getTiles()) {
          for (Tile[] row : plane) {
            for (Tile tile : row) {
              if (tile == null) {
                continue;
              }
              GameObject[] objects = tile.getGameObjects();
              for (GameObject object : objects) {
                addIfRock(object);
              }
            }
          }
        }
      });
      client.addChatMessage(ChatMessageType.GAMEMESSAGE, "FlosSandbox", "> Flos Addon ist aktiv :D", "FlosSandbox");
    }
  }

  @Subscribe
  public void onMenuEntryAdded(MenuEntryAdded event) {
    if (autoFighter != null) {
      autoFighter.injectNpcAction(event);
    } else {
      return;
    }

    if (autoWoodcutter != null) {
      autoWoodcutter.injectWoodcuttingOption(event);
    } else {
      return;
    }

    if (autoMiner != null) {
      autoMiner.injectWoodcuttingOption(event);
    } else {
      return;
    }

    /*
    if (event.getType() != MenuAction.EXAMINE_OBJECT.getId() || !client.isKeyPressed(KeyCode.KC_SHIFT)) {
      return;
    }


    final Tile tile = client.getScene().getTiles()[client.getPlane()][event.getActionParam0()][event.getActionParam1()];
    final TileObject tileObject = findTileObject(tile, event.getIdentifier());

    if (tileObject == null) {
      return;
    }

    client.createMenuEntry(-1)
            .setOption("-- TEST --")
            .setTarget(event.getTarget())
            .setParam0(event.getActionParam0())
            .setParam1(event.getActionParam1())
            .setIdentifier(event.getIdentifier())
            .setType(MenuAction.RUNELITE)
            .onClick(this::mineClickAction);
     */
  }

  private void mineClickAction(MenuEntry entry) {

  }

  private TileObject findTileObject(Tile tile, int id) {
    if (tile == null) {
      return null;
    }

    final GameObject[] tileGameObjects = tile.getGameObjects();
    final DecorativeObject tileDecorativeObject = tile.getDecorativeObject();
    final WallObject tileWallObject = tile.getWallObject();
    final GroundObject groundObject = tile.getGroundObject();

    if (objectIdEquals(tileWallObject, id)) {
      return tileWallObject;
    }

    if (objectIdEquals(tileDecorativeObject, id)) {
      return tileDecorativeObject;
    }

    if (objectIdEquals(groundObject, id)) {
      return groundObject;
    }

    for (GameObject object : tileGameObjects) {
      if (objectIdEquals(object, id)) {
        return object;
      }
    }

    return null;
  }

  private boolean objectIdEquals(TileObject tileObject, int id) {
    if (tileObject == null) {
      return false;
    }

    if (tileObject.getId() == id) {
      return true;
    }

    // Menu action EXAMINE_OBJECT sends the transformed object id, not the base id, unlike
    // all the GAME_OBJECT_OPTION actions, so check the id against the impostor ids
    final ObjectComposition comp = client.getObjectDefinition(tileObject.getId());

    if (comp.getImpostorIds() != null) {
      for (int impostorId : comp.getImpostorIds()) {
        if (impostorId == id) {
          return true;
        }
      }
    }

    return false;
  }

  @Subscribe
  public void onGameObjectDespawned(GameObjectDespawned event) {
    //ores.remove(event.getGameObject().getWorldLocation());
  }

  @Subscribe
  public void onGameObjectSpawned(GameObjectSpawned event) {
    if (client.getGameState() != GameState.LOGGED_IN) {
      return;
    }

    //addIfRock(event.getGameObject());
  }

  private void addIfRock(GameObject gameObject) {
    if (gameObject == null) {
      return;
    }
    Rock rock = Rock.getRockFromObjectId(gameObject.getId());
    if (rock == null) {
      return;
    }

    ores.put(gameObject.getWorldLocation(), new AvailableOre(rock, gameObject));
  }

  @Provides
  RuneLiteAdddonsConfig provideConfig(ConfigManager configManager) {
    return configManager.getConfig(RuneLiteAdddonsConfig.class);
  }
}
