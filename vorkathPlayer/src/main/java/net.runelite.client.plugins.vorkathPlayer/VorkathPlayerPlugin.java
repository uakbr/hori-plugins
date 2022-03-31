package net.runelite.client.plugins.vorkathPlayer;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.iutils.*;
import net.runelite.client.plugins.iutils.game.*;
import net.runelite.client.plugins.iutils.scene.Position;
import net.runelite.client.plugins.iutils.scripts.iScript;
import net.runelite.client.plugins.iutils.ui.Chatbox;
import org.pf4j.Extension;

import javax.inject.Inject;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

import static net.runelite.api.GraphicID.VORKATH_BOMB_AOE;
import static net.runelite.api.GraphicID.VORKATH_ICE;
import static net.runelite.api.ObjectID.ACID_POOL_32000;
import static net.runelite.client.plugins.vorkathPlayer.VorkathPlayerStates.*;

@Extension
@PluginDependency(iUtils.class)
@PluginDescriptor(
	name = "Vorkath Player",
	description = "Automatic Vorkath",
	tags = {"Vorkath"}
)
@Slf4j
public class VorkathPlayerPlugin extends iScript {

	@Inject
	private VorkathPlayerConfig config;

	@Inject
	private Client client;

	@Inject
	private Chatbox chatbox;

	@Inject
	private PrayerUtils prayerUtils;

	@Inject
	private iUtils utils;

	@Inject
	private InventoryUtils invUtils;

	@Inject
	private NPCUtils npcUtils;

	@Inject
	private PlayerUtils playerUtils;

	@Inject
	private WalkUtils walkUtils;

	@Inject
	private ObjectUtils objectUtils;

	@Inject
	private BankUtils bankUtils;

	@Inject
	private Game game;

	@Inject
	private CalculationUtils calc;

	private LegacyMenuEntry targetMenu;

	public HashMap<Integer, Integer> inventoryItems;
	public HashMap<Integer, Integer> itemValues;

	private boolean startPlugin = false;
	private int timeout;
	private int specCount;
	private boolean isAcid;
	private boolean isFireball;
	private boolean isMinion;
	private iGroundItem item;
	private long sleepLength;
	private boolean hasSpecced;
	private boolean shouldLoot;

	private WorldArea kickedOffIsland;
	private WorldArea afterBoat;
	private WorldPoint fireballPoint;
	private List<Integer> regions;
	public List<WorldPoint> acidSpots;
	public List<WorldPoint> acidFreePath;
	public List<WorldPoint> safeVorkathTiles;
	public WorldPoint safeWooxTile;
	private LocalPoint meleeBaseTile;
	private LocalPoint rangeBaseTile;
	public List<TileItem> items;


	private int DIAMOND_SET;
	private int RUBY_SET;

	private String oldState = "";

	private int getSpecId(){
		return config.useSpec().getItemId();
	}

	private int getMainhandId(){
		return config.mainhand().getItemId();
	}

	private int getOffhandId(){
		return config.offhand().getItemId();
	}

	private int getStaffId(){
		return config.staffID();
	}

	private int getFoodId(){
		return config.food().getId();
	}

	private int getWalkMethod(){
		return config.walkMethod().getId();
	}

	public VorkathPlayerPlugin() {
		regions = Arrays.asList(7513, 7514, 7769, 7770, 8025, 8026);
		meleeBaseTile = new LocalPoint(6208, 7744);
		rangeBaseTile = new LocalPoint(6208, 7104);
		kickedOffIsland = new WorldArea(new WorldPoint(2626, 3666, 0), new WorldPoint(2649, 3687, 0));
		afterBoat = new WorldArea(new WorldPoint(2277, 4034, 0), new WorldPoint(2279, 4036, 0));
		acidSpots = new ArrayList<>();
		acidFreePath = new ArrayList<>();
		safeVorkathTiles = new ArrayList<>();
		itemValues = new HashMap<>();
		items = new ArrayList<>();
		safeWooxTile = null;
		fireballPoint = null;
		isMinion = false;
		isAcid = false;
		isFireball = false;

		timeout = 0;
		specCount = 0;

		shouldLoot = false;
	}

	@Provides
	VorkathPlayerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(VorkathPlayerConfig.class);
	}

	@Override
	protected void startUp() {
		log.info("Vorkath Player Started");
	}

	@Override
	protected void shutDown() {
		log.info("Vorkath Player Stopped");
	}

	@Override
	protected void onStart() {

	}

	@Override
	protected void onStop() {
		startPlugin = false;
		inventoryItems.clear();
		timeout = 0;
		isMinion = false;
		isAcid = false;
		isFireball = false;
		specCount = 0;
		fireballPoint = null;
		safeWooxTile = null;
		acidFreePath .clear();
		safeVorkathTiles.clear();;
		acidFreePath.clear();
		hasSpecced = false;
		shouldLoot = false;
	}

	@Override
	protected void loop() {
		if(client.getGameState() != GameState.LOGGED_IN || !startPlugin || client.getLocalPlayer() == null) return;

		if(shouldLoot()){
			if(invUtils.isFull()){
				if(invUtils.containsItem(ItemID.VIAL)){
					useItem(getWidgetItem(Set.of(ItemID.VIAL)), MenuAction.ITEM_FIFTH_OPTION);
				}
				if(config.eatLoot() && getFood() != null){
					useItem(getFood(), MenuAction.ITEM_FIRST_OPTION);
				}
			}else{
				if(getLoot() != null && !playerUtils.isMoving()) getLoot().interact("Take");
			}
		}
		game.tick();
	}

	@Subscribe
	public void onGameTick(GameTick event){

		if(client.getGameState() != GameState.LOGGED_IN || !startPlugin || client.getLocalPlayer() == null) return;

		final Widget runWidget = client.getWidget(WidgetInfo.MINIMAP_RUN_ORB);
		final Widget prayerWidget = client.getWidget(WidgetInfo.MINIMAP_QUICK_PRAYER_ORB);

		log.info(String.valueOf(getState()));

		if(timeout > 0){
			--timeout;
			return;
		}

		final Player player = client.getLocalPlayer();
		final LocalPoint playerLocal = player.getLocalLocation();
		final WorldPoint playerWorld = player.getWorldLocation();
		final WorldPoint baseTile = config.useRange() ? WorldPoint.fromLocal(client, rangeBaseTile) : WorldPoint.fromLocal(client, meleeBaseTile);
		final iNPC vorkathAlive = game.npcs().withId(NpcID.VORKATH_8061).nearest();
		final iNPC vorkathAsleep = game.npcs().withId(NpcID.VORKATH_8059).nearest();


		if(!isAcid()){
			isAcid = false;
			safeWooxTile = null;
			acidFreePath.clear();
			acidSpots.clear();
		}

		if(!isAtVorkath()){
			isMinion = false;
			isAcid = false;
			isFireball = false;
			hasSpecced = false;
			safeVorkathTiles.clear();
		}

		if(isAtVorkath()){

			createSafetiles();

			switch (getState()){
				case EAT_FOOD:
					useItem(getFood(), MenuAction.ITEM_FIRST_OPTION);
					break;
				case DRINK_ANTIVENOM:
					useItem(getWidgetItem(config.antivenom().getIds()), MenuAction.ITEM_FIRST_OPTION);
					timeout+=1;
					break;
				case DRINK_ANTIFIRE:
					useItem(getWidgetItem(config.antifire().getIds()), MenuAction.ITEM_FIRST_OPTION);
					timeout+=1;
					break;
				case DRINK_RESTORE:
					useItem(getWidgetItem(config.prayer().getIds()), MenuAction.ITEM_FIRST_OPTION);
					timeout+=1;
					break;
				case DRINK_BOOST:
					useItem(getWidgetItem(config.boostPotion().getIds()), MenuAction.ITEM_FIRST_OPTION);
					timeout+=1;
					break;
				case TOGGLE_RUN:
					toggleRun();
					break;
				case PRAYER_ON:
					if(!prayerUtils.isQuickPrayerActive() && prayerUtils.getPoints() > 0) prayerUtils.toggleQuickPrayer(true, sleepDelay());
					break;
				case PRAYER_OFF:
					if(prayerUtils.isQuickPrayerActive()) prayerUtils.toggleQuickPrayer(false, sleepDelay());
					break;
				case EQUIP_MH:
					useItem(getWidgetItem(Set.of(getMainhandId())), MenuAction.ITEM_SECOND_OPTION);
					break;
				case EQUIP_OH:
					useItem(getWidgetItem(Set.of(getOffhandId())), MenuAction.ITEM_SECOND_OPTION);
					break;
				case EQUIP_SPEC:
					if(invUtils.isFull() && getOffhandId() != -1){
						if(getFood() != null) useItem(getFood(), MenuAction.ITEM_FIRST_OPTION);
					}
					useItem(getWidgetItem(Set.of(getSpecId())), MenuAction.ITEM_SECOND_OPTION);
					break;
				case EQUIP_STAFF:
					useItem(getWidgetItem(Set.of(getStaffId())), MenuAction.ITEM_SECOND_OPTION);
					break;
				case TOGGLE_SPEC:
					toggleSpec();
					break;
				case POKE_VORKATH:
					actionNPC(vorkathAsleep.id(), MenuAction.NPC_FIRST_OPTION);
					break;
				case LOOT_VORKATH:
					shouldLoot = true;
					break;
				case DISTANCE_CHECK:
					walkUtils.sceneWalk(baseTile, 0, sleepDelay());
					break;
				case SWITCH_DIAMOND:
					useItem(getWidgetItem(Set.of(DIAMOND_SET)), MenuAction.ITEM_SECOND_OPTION);
					break;
				case SWITCH_RUBY:
					useItem(getWidgetItem(Set.of(RUBY_SET)), MenuAction.ITEM_SECOND_OPTION);
					break;
				case ACID_WALK:
					if (runWidget != null && playerUtils.isRunEnabled() && player.isMoving()) {
						toggleRun();
					}

					if(prayerWidget != null && prayerUtils.isQuickPrayerActive() && (config.walkMethod().getId() != 2 || (config.walkMethod().getId() == 2 && player.isMoving()))){
						utils.doInvokeMsTime(new LegacyMenuEntry("Deactivate", "", 1, MenuAction.CC_OP, -1,
								10485775, false), 0);
					}

					if(config.eatWoox() && shouldEat()){
						useItem(getFood(), MenuAction.ITEM_FIRST_OPTION);
					}

					if(config.walkMethod().getId() == 1) return;
					if(config.walkMethod().getId() == 2){
						if(!acidSpots.isEmpty()){
							if(acidFreePath.isEmpty()){
								calculateAcidFreePath();
							}

							WorldPoint firstTile;
							WorldPoint lastTile;
							if(!acidFreePath.isEmpty()){
								firstTile = acidFreePath.get(0);
							}else{
								return;
							}

							if(acidFreePath.size() > 5){
								lastTile = acidFreePath.get(4);
							}else{
								lastTile = acidFreePath.get(acidFreePath.size() - 1);
							}

							log.info("First tile: " + firstTile);
							log.info("Last Tile: " + lastTile);
							log.info("Actual length: " + (firstTile.getX() != lastTile.getX() ? Math.abs(firstTile.getX() - lastTile.getX()) : Math.abs(firstTile.getY() - lastTile.getY())));

						/*if(playerUtils.isRunEnabled() && !player.getWorldLocation().equals(firstTile) && !player.getWorldLocation().equals(lastTile) && player.isMoving()){
							playerUtils.enableRun(runOrb.getBounds());
						}

						 */
							if(acidFreePath.contains(player.getWorldLocation())){
								if(player.getWorldLocation().equals(firstTile)){
									walkUtils.sceneWalk(lastTile, 0, sleepDelay());
								}
								if(player.getWorldLocation().equals(lastTile)){
									walkUtils.sceneWalk(firstTile, 0, sleepDelay());
								}
							}else if(!player.isMoving()){
								walkUtils.sceneWalk(lastTile, 0, 0);
							}

						}
					}
					else {
						Collections.sort(safeVorkathTiles, Comparator.comparingInt(o -> o.distanceTo(player.getWorldLocation())));

						if (safeWooxTile == null) {
							for (int i = 0; i < safeVorkathTiles.size(); i++) {
								WorldPoint temp = safeVorkathTiles.get(i);
								WorldPoint temp2 = new WorldPoint(temp.getX(), temp.getY() - 1 , temp.getPlane());
								if (!acidSpots.contains(temp) && !acidSpots.contains(temp2)) {
									safeWooxTile = temp2;
									break;
								}
							}
						}

						if(safeWooxTile != null){
							if(player.getWorldLocation().equals(safeWooxTile)){
								actionNPC(vorkathAlive.id(), MenuAction.NPC_SECOND_OPTION);
							}else{
								LocalPoint lp = LocalPoint.fromWorld(client, safeWooxTile);
								if(lp != null){
									if(config.invokes()){
										walkUtils.walkTile(lp.getSceneX(), lp.getSceneY());
									}else{
										walkUtils.sceneWalk(lp, 0, 0);
									}
								}else{
									log.info("Local point is a null");
								}
							}
						}
					}

					break;
				case KILL_MINION:
					NPC iceMinion = npcUtils.findNearestNpc(NpcID.ZOMBIFIED_SPAWN_8063);

					if(player.getInteracting() != null && player.getInteracting().getName().equalsIgnoreCase("Vorkath")){
						walkUtils.sceneWalk(playerLocal, 0, sleepDelay());
						return;
					}
					if(prayerUtils.isQuickPrayerActive()){
						prayerUtils.toggleQuickPrayer(false, sleepDelay());
						return;
					}
					if(iceMinion != null && player.getInteracting() == null) {
						attackMinion();
						timeout+=4;
					}
					break;
				case DODGE_FIREBALL:
					LocalPoint bomb = LocalPoint.fromWorld(client, fireballPoint);
					LocalPoint dodgeRight = new LocalPoint(bomb.getX() + 256, bomb.getY()); //Local point is 1/128th of a tile. 256 = 2 tiles
					LocalPoint dodgeLeft = new LocalPoint(bomb.getX() - 256, bomb.getY());
					LocalPoint dodgeReset = new LocalPoint(6208, 7872);

					if(isFireball && !player.getWorldLocation().equals(fireballPoint)){
						fireballPoint = null;
						isFireball = false;
						return;
					}
					if(playerLocal.getY() > 7872){
						walkUtils.sceneWalk(dodgeReset, 0, 0);
						isFireball = false;
						timeout+=2;
						return;
					}
					if (playerLocal.getX() < 6208) {
						walkUtils.sceneWalk(dodgeRight, 0, 0);
					} else {
						walkUtils.sceneWalk(dodgeLeft, 0, 0);
					}
					break;
				case RETALIATE:
					if(vorkathAlive != null){
						actionNPC(vorkathAlive.id(), MenuAction.NPC_SECOND_OPTION);
					}
					break;
				case TELEPORT_TO_POH:
					teleToPoH();
					break;
			}
		}
		else if (isInPOH()){
			switch (getState()){
				case USE_POOL:
					if(prayerUtils.isQuickPrayerActive()) prayerUtils.toggleQuickPrayer(false, sleepDelay());
					GameObject poolObject = objectUtils.findNearestGameObject(config.poolID());

					if(poolObject != null && !player.isMoving())
						actionObject(poolObject.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION, null);

					timeout+=1;
					break;
				case USE_PORTAL:
					GameObject portalObject = objectUtils.findNearestGameObject(config.moonclanTeleport());

					if(portalObject != null && !player.isMoving())
						actionObject(portalObject.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION, null);

					timeout+=1;
					break;
				case TIMEOUT:
					break;
			}
		}
		else if(isNearBank()){
			switch(getState()){
				case FIX_GEAR:
					if(invUtils.getEmptySlots() < 6){
						if(bankUtils.isOpen()){
							bankUtils.depositAll();
							timeout+=1;
						}else{
							openBank();
						}
						return;
					}
					if(getSpecId() != -1 && !isItemEquipped(getSpecId())){
						withdrawUse(new HashMap<Integer, Integer>() {{
							put(getSpecId(), 1);
						}}, MenuAction.ITEM_SECOND_OPTION);
						timeout+=1;
					}
					if(getSpecId() == -1){
						withdrawUse(new HashMap<Integer, Integer>() {{
							put(getMainhandId(), 1);
							if(getOffhandId() != -1) put(getOffhandId(), 1);
						}}, MenuAction.ITEM_SECOND_OPTION);

						timeout=1;
					}
					if(config.useRange() && config.useDiamond() && !playerUtils.isItemEquipped(Set.of(RUBY_SET))){
						withdrawUse(new HashMap<Integer, Integer>() {{
							put(RUBY_SET, -1);
						}}, MenuAction.ITEM_SECOND_OPTION);
					}
					break;
				case OVEREAT:
					if(config.overEat() && getFoodId() == ItemID.ANGLERFISH && game.modifiedLevel(Skill.HITPOINTS) <= game.baseLevel(Skill.HITPOINTS)){
						withdrawUse(new HashMap<Integer, Integer>() {{
							put(getFoodId(), 1);
						}}, MenuAction.ITEM_FIRST_OPTION);
					}
					if(!config.usePool() && (game.modifiedLevel(Skill.HITPOINTS) < game.baseLevel(Skill.HITPOINTS) || game.modifiedLevel(Skill.PRAYER) < game.baseLevel(Skill.PRAYER))) {
						withdrawUse(new HashMap<Integer, Integer>() {{
							if(game.modifiedLevel(Skill.HITPOINTS) < game.baseLevel(Skill.HITPOINTS))
								put(getFoodId(), 5);
							if(game.modifiedLevel(Skill.PRAYER) < game.baseLevel(Skill.PRAYER))
								put(config.prayer().getDose4(), 2);
						}}, MenuAction.ITEM_FIRST_OPTION);
					}
						break;
				case WITHDRAW_INVENTORY:
					if(containsExcept(inventoryItems.keySet())){
						if(bankUtils.isOpen()){
							bankUtils.depositAll();
							timeout+=1;
						}else{
							openBank();
						}
						return;
					}
					if(bankUtils.isOpen()) {
						for (int id : inventoryItems.keySet()) {
							if(!bankUtils.contains(id, 1) && !invUtils.containsItem(id)){
								game.sendGameMessage("Failed to find id: " + id);
								stop();
								return;
							}

							if(client.getItemComposition(id).getName().contains("bolt")
								&& !invUtils.containsItemAmount(id, inventoryItems.get(id), true, true)){
								bankUtils.withdrawItemAmount(id, (inventoryItems.get(id) - invUtils.getItemCount(id, true)));
								timeout+=3;
								return;
							}else if (!client.getItemComposition(id).getName().contains("bolt")){
								if (!invUtils.containsItemAmount(id, inventoryItems.get(id), false, true)) {
									if(invUtils.getItemCount(id, false) > inventoryItems.get(id)) {
										bankUtils.depositAll();
										return;
									}
									bankUtils.withdrawItemAmount(id, (inventoryItems.get(id) - invUtils.getItemCount(id, false)));
									timeout += inventoryItems.get(id) == 1 ? 0 : 3;
									return;
								}
							}
						}
					}else{
						openBank();
					}
					break;
				case LEAVE_BANK:
					switch(config.rellekkaTeleport().getOption()){
						case 0:
							GameObject badBooth = objectUtils.getGameObjectAtWorldPoint(new WorldPoint(2098, 3920, 0));
							if(chatboxIsOpen()){
								continueChat();
							}else
							if(badBooth != null && !player.isMoving()){
								actionObject(badBooth.getId(), MenuAction.GAME_OBJECT_SECOND_OPTION, badBooth.getWorldLocation());
								timeout+=2;
							}
							break;
						case 1:
							useItem(getWidgetItem(Set.of(ItemID.FREMENNIK_SEA_BOOTS_4)), MenuAction.ITEM_THIRD_OPTION);
							timeout+=7;
							break;
						case 29712:
							GameObject returnOrb = objectUtils.findNearestGameObject(29712);

							if(!chatboxIsOpen()){
								if(returnOrb != null && !player.isMoving()){
									actionObject(returnOrb.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION, null);
								}
							}else{
								chatbox.chooseOption("Yes");
								timeout+=13;
							}


					}
					break;
			}
		}
		else{
			switch (getState()){
				case USE_BOAT:
					actionObject(29917, MenuAction.GAME_OBJECT_FIRST_OPTION, null);
					break;
				case USE_OBSTACLE:
					actionObject(31990, MenuAction.GAME_OBJECT_FIRST_OPTION, null);
					break;
			}
		}

	}

	public void withdrawUse(HashMap<Integer, Integer> set, MenuAction action){

		List<Integer> temp = new ArrayList<>(set.keySet());
		temp.removeIf(a -> equipment.isEquipped(a));
		Collection<Integer> temp1 = new ArrayList<>(temp);

		if(invUtils.containsAllOf(temp)){
			useItem(getWidgetItem(temp1), action);
		}else{
			withdrawList(set);
		}
	}

	boolean chatboxIsOpen() {
		return chatbox.chatState() == Chatbox.ChatState.NPC_CHAT || chatbox.chatState() == Chatbox.ChatState.PLAYER_CHAT || chatbox.chatState() == Chatbox.ChatState.OPTIONS_CHAT;
	}

	public void withdrawList(HashMap<Integer, Integer> list){
		if(bankUtils.isOpen()){
			list.forEach((k, v) -> {
				if(!invUtils.containsItem(k)) {
					if (v >= 1) {
						bankUtils.withdrawItemAmount(k, v);
					} else
						bankUtils.withdrawAllItem(k);
					}
					timeout+=1;
				;
			});
		}else{
			openBank();
		}
	}

	@Subscribe
	private void onChatMessage(ChatMessage ev){
		if(!startPlugin || ev.getType() != ChatMessageType.GAMEMESSAGE) return;

		String message = ev.getMessage();

		String deathMessage = "Oh dear, you are dead!";

		if(message.equalsIgnoreCase(deathMessage)){
			timeout+=2;
			game.sendGameMessage("You suck omegal0l");
			stop();
		}
	}

	@Subscribe
	private void onConfigButtonPressed(ConfigButtonClicked configButtonClicked) {
		if (!configButtonClicked.getGroup().equalsIgnoreCase("VorkathPlayerConfig"))
			return;

		if (configButtonClicked.getKey().equalsIgnoreCase("startVorkath")) {
			if (!startPlugin) {
				game.sendGameMessage("Vorkath Started");
				startPlugin = true;

				inventoryItems = new HashMap<>();
				initInventoryItems();
				start();
			} else {
				game.sendGameMessage("Vorkath stopped");
				startPlugin = false;
				stop();
			}
		}
	}

	@Subscribe
	private void onAnimationChanged(AnimationChanged event) {
		if(!startPlugin || event == null) return;

		final Player player = client.getLocalPlayer();

		final WorldPoint playerLocation = player.getWorldLocation();
		final LocalPoint playerLocalPoint = player.getLocalLocation();
		final Actor actor = event.getActor();

		if (actor == player) {
			if (actor.getAnimation() == 7642 || actor.getAnimation() == 1378 || actor.getAnimation() == 7514)  { //bgs dwh claws
				hasSpecced = true;
			}
		}
		if(actor.getAnimation() == 7949){ //death animation
			hasSpecced = false;
		}
		if(actor.getAnimation() == 7889){
			isMinion = false;
		}
		if(actor.getAnimation() == 7957 && actor.getName().equalsIgnoreCase("Vorkath")){
			timeout+=1;
		}
	}

	@Subscribe
	private void onProjectileSpawned(ProjectileSpawned event) {
		if(client.getLocalPlayer() == null) return;

		final Player player = client.getLocalPlayer();

		final Projectile projectile = event.getProjectile();

		final WorldPoint playerWorldLocation = player.getWorldLocation();

		final LocalPoint playerLocalLocation = player.getLocalLocation();

		if (projectile.getId() == VORKATH_BOMB_AOE){
			fireballPoint = player.getWorldLocation();
			isFireball = true;
		}

		if (projectile.getId() == VORKATH_ICE) {
			isMinion = true;
		}

		if(projectile.getId() == 1483)
			isAcid = true;
	}

	@Subscribe
	private void onProjectileMoved(final ProjectileMoved event) {
		Projectile projectile = event.getProjectile();
		LocalPoint position = event.getPosition();
		WorldPoint.fromLocal(
				client,
				position);
		if(client.getLocalPlayer() != null) {
			LocalPoint fromWorld = LocalPoint.fromWorld(client, client.getLocalPlayer().getWorldLocation());

			if (projectile.getId() == 1483) {
				addAcidSpot(WorldPoint.fromLocal(client, position));
			}
		}
	}


	/* Functions below */

	private VorkathPlayerStates getState(){

		Player player = client.getLocalPlayer();

		if(isAtVorkath()) { //Vorkath FIGHT LOGIC

			iNPC vorkathAlive = game.npcs().withId(NpcID.VORKATH_8061).nearest();
			final WorldPoint baseTile = config.useRange() ? WorldPoint.fromLocal(client, rangeBaseTile) : WorldPoint.fromLocal(client, meleeBaseTile);

			if(!isFireball && !isAcid) {
				if (shouldDrinkVenom()) return DRINK_ANTIVENOM;
				if (shouldDrinkAntifire()) return DRINK_ANTIFIRE;
				if (shouldDrinkBoost()) return DRINK_BOOST;
				if (shouldDrinkRestore()) return DRINK_RESTORE;
			}

			if(shouldEat() && !isAcid() && !isFireball) return EAT_FOOD;

			if((vorkathAlive != null || isWakingUp())
					&& !isAcid
					&& !isMinion
					&& !isFireball
					&& !canSpec()
					&& !shouldLoot()
					&& !player.isMoving()
					&& (config.useRange() ? baseTile.distanceTo(player.getWorldLocation()) >= 4 : baseTile.distanceTo(player.getWorldLocation()) >= 4))
				return DISTANCE_CHECK;


			if(isAcid) return ACID_WALK;

			if(isMinion) {
				if(config.useStaff() && !isItemEquipped(getStaffId())) return EQUIP_STAFF;
				return KILL_MINION;
			}

			if(isFireball) return DODGE_FIREBALL;

			if(prayerUtils.isQuickPrayerActive()
					&& prayerUtils.getPoints() > 0
					&& (isVorkathAsleep()
					|| (vorkathAlive != null && vorkathAlive.isDead())
					|| isMinion
					|| isAcid))
				return PRAYER_OFF;

			if(prayerUtils.getPoints() > 0 && !prayerUtils.isQuickPrayerActive()
					&& ((vorkathAlive != null
					&& !vorkathAlive.isDead()
					&& !isAcid
					&& !isMinion) || isWakingUp()))
				return PRAYER_ON;

			if(canSpec() && (isWakingUp() || (vorkathAlive != null && !vorkathAlive.isDead()))){
				if(isItemEquipped(getSpecId())){
					if(isSpecActive()){
						return RETALIATE;
					}else{
						return TOGGLE_SPEC;
					}
				}else{
					return EQUIP_SPEC;
				}
			}

			if(config.useDiamond() && vorkathAlive == null
					&& playerUtils.isItemEquipped(Set.of(DIAMOND_SET)) && invUtils.containsItem(RUBY_SET))
				return SWITCH_RUBY;

			if(config.useDiamond() && vorkathAlive != null && !vorkathAlive.isDead()
					&& playerUtils.isItemEquipped(Set.of(RUBY_SET))
					&& invUtils.containsItem(DIAMOND_SET)
					&& calculateHealth(vorkathAlive) > 0
					&& calculateHealth(vorkathAlive) < 260
					&& vorkathAlive.animation() != 7960
					&& vorkathAlive.animation() != 7957)
				return SWITCH_DIAMOND;

			if(!config.useStaff() || (config.useStaff() && !isMinion)){
				if(vorkathAlive != null && !isItemEquipped(getMainhandId())) return EQUIP_MH;
				if(vorkathAlive != null && getOffhandId() != -1 && !isItemEquipped(getOffhandId())) return EQUIP_OH;
			}

			if(config.useStaff() && isMinion && !isItemEquipped(getStaffId())) return EQUIP_STAFF;

			if(player.getInteracting() == null
					&& vorkathAlive != null && !vorkathAlive.isDead()
					&& !isMinion
					&& !isFireball
					&& !isAcid
					&& !isWakingUp())
				return RETALIATE;

			if(!player.isMoving() && !shouldLoot() && isVorkathAsleep() && !shouldDrinkRestore() && !shouldDrinkBoost() && !shouldDrinkAntifire() && !shouldDrinkVenom() && hasFoodForKill() && (game.modifiedLevel(Skill.HITPOINTS) >= (game.baseLevel(Skill.HITPOINTS) - 20)))
				return POKE_VORKATH;

			if(shouldLoot()
					&& (!invUtils.isFull() || (invUtils.isFull() && (config.eatLoot() && getFood() != null) || invUtils.containsItem(ItemID.VIAL))))
				return LOOT_VORKATH;

			if((isVorkathAsleep() && shouldLoot() && ((invUtils.isFull() && !invUtils.containsItem(ItemID.VIAL) && getFood() == null))) || (!config.eatLoot() && invUtils.isFull() && getFood() == null) || (vorkathAlive != null && getFood() == null && game.modifiedLevel(Skill.HITPOINTS) <= config.eatAt()) || (!shouldLoot() && (prayerUtils.getPoints() == 0 && getWidgetItem(config.prayer().getIds()) == null) || (game.modifiedLevel(Skill.HITPOINTS) <= 5) || (shouldDrinkVenom() && getWidgetItem(config.antivenom().getIds()) == null) || (shouldDrinkAntifire() && getWidgetItem(config.antifire().getIds()) == null)) || (!hasFoodForKill() && isVorkathAsleep()))
				return TELEPORT_TO_POH;

			if(!playerUtils.isRunEnabled() && !isAcid) return TOGGLE_RUN;

		}

		if(isInPOH()){
			if (config.usePool() && (game.modifiedLevel(Skill.HITPOINTS) < game.baseLevel(Skill.HITPOINTS)
					|| game.modifiedLevel(Skill.PRAYER) < game.baseLevel(Skill.PRAYER)
					|| client.getVar(VarPlayer.SPECIAL_ATTACK_PERCENT) < 1000)) {
				return USE_POOL;
			}
			return USE_PORTAL;
		}

		if(isNearBank()){
			if(!isGeared())
				return FIX_GEAR;
			if(shouldEatAtBank())
				return OVEREAT;
			if(!checkItems())
				return WITHDRAW_INVENTORY;
			return LEAVE_BANK;
		}

		if(kickedOffIsland.intersectsWith(player.getWorldArea()) && !player.isMoving()){
			return USE_BOAT;
		}
		if(afterBoat.intersectsWith(player.getWorldArea()) && !player.isMoving()){
			return USE_OBSTACLE;
		}

		return TIMEOUT;
	}

	public void createSafetiles(){
		if(isAtVorkath()){
			if(safeVorkathTiles.size() > 8) safeVorkathTiles.clear();
			LocalPoint southWest = config.walkMethod().getId() == 3 ? new LocalPoint(5824, 7872) : config.walkMethod().getId() == 4 ? new LocalPoint(5824, 7104) : new LocalPoint(5824, 7360);
			WorldPoint base = WorldPoint.fromLocal(client, southWest);
			for(int i = 0; i < 7; i++){
				safeVorkathTiles.add(new WorldPoint(base.getX() + i, base.getY(), base.getPlane()));
			}
		}else if(!isAtVorkath() && !safeVorkathTiles.isEmpty()){
			safeVorkathTiles.clear();
		}
	}

	private boolean isInPOH() {
		int[] mapRegions = client.getMapRegions();
		return Arrays.stream(mapRegions).anyMatch(e -> regions.contains(e));
	}

	public boolean isAtVorkath(){
		iNPC vorkath = game.npcs().withName("Vorkath").nearest();
		return client.isInInstancedRegion() && vorkath != null;
	}

	public boolean isSpecActive(){
		return game.varp(VarPlayer.SPECIAL_ATTACK_ENABLED.getId()) == 1;
	}

	public boolean shouldDrinkBoost(){
		Skill skill = config.boostPotion().getSkill();
		return game.modifiedLevel(skill) <= config.boostLevel() && game.modifiedLevel(Skill.HITPOINTS) > 40 && getWidgetItem(config.boostPotion().getIds()) != null;
	}

	public boolean shouldDrinkVenom() {
		return !isItemEquipped(ItemID.SERPENTINE_HELM) && game.varp(VarPlayer.POISON.getId()) > 0 && getWidgetItem(config.antivenom().getIds()) != null;
	}

	public boolean shouldEat(){
		return (game.modifiedLevel(Skill.HITPOINTS) <= config.eatAt() && getFood() != null) || (isAtVorkath() && isVorkathAsleep() && (game.modifiedLevel(Skill.HITPOINTS) <= game.baseLevel(Skill.HITPOINTS) - 20) && getFood() != null);
	}

	public boolean shouldDrinkAntifire(){
		return config.antifire().name().toLowerCase().contains("super") ? game.varb(6101) == 0 : game.varb(3981) == 0 && getWidgetItem(config.antifire().getIds()) != null;
	}

	public boolean shouldDrinkRestore(){
		return prayerUtils.getPoints() < config.restoreAt() && getWidgetItem(config.prayer().getIds()) != null;
	}

	public boolean isNearBank(){
		iNPC goodBanker = game.npcs().withName("'Bird's-Eye' Jack").nearest();
		return goodBanker != null;
	}


	public boolean isItemEquipped(int id){
		return game.equipment().withId(id).exists();
	}

	private void addAcidSpot(WorldPoint worldPoint) {
		if (!acidSpots.contains(worldPoint))
			acidSpots.add(worldPoint);
	}

	public boolean shouldLoot(){
		iNPC vork = game.npcs().withId(NpcID.VORKATH_8061).first();

		if(getLoot() != null && getLoot().id() == ItemID.SUPERIOR_DRAGON_BONES && invUtils.isFull() && config.lootBonesIfRoom() && hasFoodForKill()) return false;

		return getLoot() != null && (isVorkathAsleep());
	}



	public iGroundItem getLoot(){
		iNPC vork = game.npcs().withId(NpcID.VORKATH_8061).first();

		List<iGroundItem> loot = game.groundItems().filter(a -> {

			int value = 0;
			if(itemValues.containsKey(a.id())){
				value = itemValues.get(a.id()) * a.quantity();
			}else{
				itemValues.put(a.id(), game.getFromClientThread(() -> utils.getItemPrice(a.id(), true)));
			}

			return value >= config.lootValue() || (a.id() == ItemID.BLUE_DRAGONHIDE && a.quantity() > 3) || (config.lootBones() && a.id() == ItemID.SUPERIOR_DRAGON_BONES) || (config.lootHide() && a.id() == ItemID.BLUE_DRAGONHIDE);
		}).sorted(Comparator.comparingInt(b -> {
			return itemValues.get(b.id()) * b.quantity();

		})).collect(Collectors.toList());

		Collections.reverse(loot);

		if(!loot.isEmpty()) return loot.get(0);

		return null;
	}

	public boolean isVorkathAsleep(){
		iNPC vorkathAsleep = game.npcs().withId(NpcID.VORKATH_8059).first();
		return isAtVorkath() && vorkathAsleep != null;
	}

	public boolean isWakingUp(){
		NPC vorkathWaking = npcUtils.findNearestNpc(NpcID.VORKATH_8058);
		return isAtVorkath() && vorkathWaking != null;
	}

	public boolean containsExcept(Collection<Integer> itemIds){
		for(InventoryItem item : game.inventory().all()){
			if(!itemIds.contains(item.id())){
				return true;
			}
		}
		return false;
	}

	public boolean isAcid(){
		GameObject pool = objectUtils.findNearestGameObject(ACID_POOL_32000);
		NPC vorkath = npcUtils.findNearestNpc(NpcID.VORKATH_8061);
		return pool != null || (vorkath != null && vorkath.getAnimation() == 7957);
	}

	private int calculateHealth(iNPC target) {
		// Based on OpponentInfoOverlay HP calculation & taken from the default slayer plugin
		if (target == null || target.name() == null)
		{
			return -1;
		}

		final int healthScale = target.getHealthScale();
		final int healthRatio = target.getHealthRatio();
		final int maxHealth = 750;

		if (healthRatio < 0 || healthScale <= 0)
		{
			return -1;
		}

		return (int)((maxHealth * healthRatio / healthScale) + 0.5f);
	}

	private void calculateAcidFreePath()
	{
		acidFreePath.clear();

		Player player = client.getLocalPlayer();
		NPC vorkath = npcUtils.findNearestNpc(NpcID.VORKATH_8061);

		if (vorkath == null)
		{
			return;
		}

		final int[][][] directions = {
				{
						{0, 1}, {0, -1} // Positive and negative Y
				},
				{
						{1, 0}, {-1, 0} // Positive and negative X
				}
		};

		List<WorldPoint> bestPath = new ArrayList<>();
		double bestClicksRequired = 99;

		final WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();
		final WorldPoint vorkLoc = vorkath.getWorldLocation();
		final int maxX = vorkLoc.getX() + 14;
		final int minX = vorkLoc.getX() - 8;
		final int maxY = vorkLoc.getY() - 1;
		final int minY = vorkLoc.getY() - 8;

		// Attempt to search an acid free path, beginning at a location
		// adjacent to the player's location (including diagonals)
		for (int x = -1; x < 2; x++)
		{
			for (int y = -1; y < 2; y++)
			{
				final WorldPoint baseLocation = new WorldPoint(playerLoc.getX() + x,
						playerLoc.getY() + y, playerLoc.getPlane());

				if (acidSpots.contains(baseLocation) || baseLocation.getY() < minY || baseLocation.getY() > maxY)
				{
					continue;
				}

				// Search in X and Y direction
				for (int d = 0; d < directions.length; d++)
				{
					// Calculate the clicks required to start walking on the path
					double currentClicksRequired = Math.abs(x) + Math.abs(y);
					if (currentClicksRequired < 2)
					{
						currentClicksRequired += Math.abs(y * directions[d][0][0]) + Math.abs(x * directions[d][0][1]);
					}
					if (d == 0)
					{
						// Prioritize a path in the X direction (sideways)
						currentClicksRequired += 0.5;
					}

					List<WorldPoint> currentPath = new ArrayList<>();
					currentPath.add(baseLocation);

					// Positive X (first iteration) or positive Y (second iteration)
					for (int i = 1; i < 25; i++)
					{
						final WorldPoint testingLocation = new WorldPoint(baseLocation.getX() + i * directions[d][0][0],
								baseLocation.getY() + i * directions[d][0][1], baseLocation.getPlane());

						if (acidSpots.contains(testingLocation) || testingLocation.getY() < minY || testingLocation.getY() > maxY
								|| testingLocation.getX() < minX || testingLocation.getX() > maxX)
						{
							break;
						}

						currentPath.add(testingLocation);
					}

					// Negative X (first iteration) or positive Y (second iteration)
					for (int i = 1; i < 25; i++)
					{
						final WorldPoint testingLocation = new WorldPoint(baseLocation.getX() + i * directions[d][1][0],
								baseLocation.getY() + i * directions[d][1][1], baseLocation.getPlane());

						if (acidSpots.contains(testingLocation) || testingLocation.getY() < minY || testingLocation.getY() > maxY
								|| testingLocation.getX() < minX || testingLocation.getX() > maxX)
						{
							break;
						}

						currentPath.add(testingLocation);
					}

					if (currentPath.size() >= 5 && currentClicksRequired < bestClicksRequired
							|| (currentClicksRequired == bestClicksRequired && currentPath.size() > bestPath.size()))
					{
						bestPath = currentPath;
						bestClicksRequired = currentClicksRequired;
					}
				}
			}
		}

		if (bestClicksRequired != 99)
		{
			acidFreePath = bestPath;
		}
	}

	private long sleepDelay() {
		sleepLength = calc.randomDelay(config.sleepWeightedDistribution(), config.sleepMin(), config.sleepMax(), config.sleepDeviation(), config.sleepTarget());
		return sleepLength;
	}

	private int tickDelay() {
		int tickLength = (int) calc.randomDelay(config.tickDelaysWeightedDistribution(), config.tickDelaysMin(), config.tickDelaysMax(), config.tickDelaysDeviation(), config.tickDelaysTarget());
		return tickLength;
	}

	private void useItem(WidgetItem item, MenuAction action) {
		if (item != null) {
			targetMenu = new LegacyMenuEntry("", "", item.getId(), action, item.getIndex(),
					WidgetInfo.INVENTORY.getId(), false);
			if (config.invokes()) {
				utils.doInvokeMsTime(targetMenu, 0);
			} else {
				utils.doActionMsTime(targetMenu, item.getCanvasBounds(), 0);
			}
		}
	}


	private WidgetItem getWidgetItem(Collection c){
		if(invUtils.containsItem(c)){
			return invUtils.getWidgetItem(c);
		}
		return null;
	}

	private WidgetItem getFood(){
		if(invUtils.containsItem(config.food().getId())){
			return invUtils.getWidgetItem(config.food().getId());
		}
		return null;
	}

	private boolean hasFoodForKill(){
		if(getFood() == null) return false;
		return invUtils.getItemCount(config.food().getId(), false) >= config.minFood();
	}

	private int getSpecialPercent(){
		return game.varp(VarPlayer.SPECIAL_ATTACK_PERCENT.getId()) / 10;
	}

	private boolean canSpec(){
		return getSpecId() != -1 && !hasSpecced && getSpecialPercent() >= config.useSpec().getSpecAmt();
	}

	private void toggleSpec(){
		Widget widget = client.getWidget(WidgetInfo.MINIMAP_SPEC_CLICKBOX);
		if(widget != null && canSpec()){
			targetMenu = new LegacyMenuEntry("<col=ff9040>Special Attack</col>", "", 1, MenuAction.CC_OP.getId(), -1, WidgetInfo.MINIMAP_SPEC_CLICKBOX.getId(), false);
			if (config.invokes())
				utils.doInvokeMsTime(targetMenu, sleepDelay());
			else
				utils.doActionMsTime(targetMenu, widget.getBounds(), sleepDelay());
		}
	}
	private void toggleRun(){
		utils.doInvokeMsTime(new LegacyMenuEntry("Toggle Run", "", 1, MenuAction.CC_OP, -1,
				10485783, false), 0);
	}

	private void actionNPC(int id, MenuAction action) {
		NPC target = npcUtils.findNearestNpc(id);
		if (target != null) {
			targetMenu = new LegacyMenuEntry("", "", target.getIndex(), action, target.getIndex(), 0, false);
			if (!config.invokes())
				utils.doNpcActionMsTime(target, action.getId(), sleepDelay());
			else
				utils.doInvokeMsTime(targetMenu, sleepDelay());
		}
	}

	private void actionObject(int id, MenuAction action, WorldPoint point) {
		GameObject obj = point == null ? objectUtils.findNearestGameObject(id) : objectUtils.getGameObjectAtWorldPoint(point);
		if (obj != null) {
			targetMenu = new LegacyMenuEntry("", "", obj.getId(), action, obj.getSceneMinLocation().getX(), obj.getSceneMinLocation().getY(), false);
			if (!config.invokes())
				utils.doGameObjectActionMsTime(obj, action.getId(), sleepDelay());
			else
				utils.doInvokeMsTime(targetMenu, sleepDelay());
		}
	}

	public void attackMinion(){
		NPC iceMinion = npcUtils.findNearestNpc(NpcID.ZOMBIFIED_SPAWN_8063);
		if(iceMinion != null && !iceMinion.isDead()) {
			LegacyMenuEntry entry = new LegacyMenuEntry("Cast", "", iceMinion.getIndex(), MenuAction.SPELL_CAST_ON_NPC.getId(), 0, 0, false);
			utils.oneClickCastSpell(WidgetInfo.SPELL_CRUMBLE_UNDEAD, entry, iceMinion.getConvexHull().getBounds(), 0);
		}
	}

	public void openBank(){
		GameObject booth = objectUtils.findNearestGameObjectMenuWithin(new WorldPoint(2099, 3920, 0), 0, "Bank");
		if(booth != null && !playerUtils.isMoving()) actionObject(booth.getId(), MenuAction.GAME_OBJECT_SECOND_OPTION, new WorldPoint(2099, 3920, 0));
	}

	private void teleToPoH() {
		switch(config.houseTele().getId()){
			case ItemID.CONSTRUCT_CAPET:
			case ItemID.CONSTRUCT_CAPE:
				useItem(getWidgetItem(Set.of(config.houseTele().getId())), MenuAction.ITEM_FOURTH_OPTION);
				break;
			case ItemID.TELEPORT_TO_HOUSE:
				useItem(getWidgetItem(Set.of(config.houseTele().getId())), MenuAction.ITEM_FIRST_OPTION);
				break;
			case -1:
				Widget widget = client.getWidget(WidgetInfo.SPELL_TELEPORT_TO_HOUSE);
				if (widget != null) {
					targetMenu = new LegacyMenuEntry("Cast", "<col=00ff00>Teleport to House</col>", 1 , MenuAction.CC_OP, -1, widget.getId(), false);
					utils.doActionMsTime(targetMenu, widget.getBounds(), (int)sleepDelay());
				}
				break;
		}
	}

	public void initInventoryItems(){
		if(getSpecId() != -1){
			if(getMainhandId() != -1) {
				inventoryItems.put(getMainhandId(), 1);
			}
			if(config.useSpec().getHands() == 2){
				if(getOffhandId() != -1){
					inventoryItems.put(getOffhandId(), 1);
				}
			}
		}
		if(config.useStaff()){
			inventoryItems.put(config.staffID(), 1);
		}

		if(config.antivenom().getDose4() != ItemID.SERPENTINE_HELM){ //venom config
			inventoryItems.put(config.antivenom().getDose4(), 1);
		}

		if(config.useRange() && config.useDiamond()){
			if(config.useDragonBolts()){
				RUBY_SET = ItemID.RUBY_DRAGON_BOLTS_E;
				DIAMOND_SET = ItemID.DIAMOND_DRAGON_BOLTS_E;

				inventoryItems.put(ItemID.DIAMOND_DRAGON_BOLTS_E, 500);
			}else{
				RUBY_SET = ItemID.RUBY_BOLTS_E;
				DIAMOND_SET = ItemID.DIAMOND_BOLTS_E;

				inventoryItems.put(ItemID.DIAMOND_BOLTS_E, 500);

			}
		}

		if(config.houseTele().getId() != -1){
			inventoryItems.put(config.houseTele().getId(), 1);
		}
		if(config.rellekkaTeleport().getOption() == 1){
			inventoryItems.put(ItemID.FREMENNIK_SEA_BOOTS_4, 1);
		}

		inventoryItems.put(config.boostPotion().getDose4(), 1);
		inventoryItems.put(config.antifire().getDose4(), 1);
		inventoryItems.put(config.pouchID(), 1);
		inventoryItems.put(config.prayer().getDose4(), config.prayerAmount());

		inventoryItems.put(getFoodId(), config.withdrawFood());
	}

	public boolean checkItems(){
		for(int id : inventoryItems.keySet()){
			if((id == ItemID.DIAMOND_DRAGON_BOLTS_E || id == ItemID.DIAMOND_BOLTS_E)
				&& invUtils.getItemCount(id, true) < 50){
				return false;
			}

			if((id != ItemID.DIAMOND_DRAGON_BOLTS_E && id != ItemID.DIAMOND_BOLTS_E) && !invUtils.containsItemAmount(id, inventoryItems.get(id), false, true)){
				return false;
			}
		}
		return true;
	}

	public boolean isGeared(){
		if(getSpecId() != -1 && !isItemEquipped(getSpecId())) return false;
		if(getSpecId() == -1 && ((getMainhandId() != -1 && !isItemEquipped(getMainhandId())) || (getOffhandId() != -1 && !isItemEquipped(getOffhandId())))) return false;
		if(config.useRange() && config.useDiamond() && !playerUtils.isItemEquipped(Set.of(RUBY_SET))) return false;

		return true;
	}

	public boolean shouldEatAtBank(){
		if(config.overEat() && getFoodId() == ItemID.ANGLERFISH && game.modifiedLevel(Skill.HITPOINTS) <= game.baseLevel(Skill.HITPOINTS)){
			return true;
		}
		if(!config.usePool() && (game.modifiedLevel(Skill.HITPOINTS) < game.baseLevel(Skill.HITPOINTS) || game.modifiedLevel(Skill.PRAYER) < game.baseLevel(Skill.PRAYER))){
			return true;
		}
		return false;
	}

	private void continueChat() {

		targetMenu = null;
		Rectangle bounds = null;
		if (chatbox.chatState() == Chatbox.ChatState.NPC_CHAT) {
			targetMenu = new LegacyMenuEntry("Continue", "", 0, MenuAction.WIDGET_TYPE_6, -1, client.getWidget(231, 5).getId(), false);
			bounds = client.getWidget(231, 5).getBounds();
		}
		if (chatbox.chatState() == Chatbox.ChatState.PLAYER_CHAT) {
			targetMenu = new LegacyMenuEntry("Continue", "", 0, MenuAction.WIDGET_TYPE_6, -1, client.getWidget(217, 5).getId(), false);
			bounds = client.getWidget(217, 5).getBounds();
		}
		if (!config.invokes() && bounds != null)
			utils.doActionMsTime(targetMenu, bounds, (int)sleepDelay());
		else
			utils.doInvokeMsTime(targetMenu, (int)sleepDelay());
	}

}
