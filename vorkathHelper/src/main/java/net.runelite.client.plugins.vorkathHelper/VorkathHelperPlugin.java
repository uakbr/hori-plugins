package net.runelite.client.plugins.vorkathHelper;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
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
import net.runelite.client.plugins.iutils.scripts.iScript;
import net.runelite.client.plugins.iutils.ui.Chatbox;
import org.pf4j.Extension;

import javax.inject.Inject;

import java.util.*;

import static net.runelite.api.GraphicID.VORKATH_BOMB_AOE;
import static net.runelite.api.GraphicID.VORKATH_ICE;
import static net.runelite.api.ObjectID.ACID_POOL_32000;


@Extension
@PluginDependency(iUtils.class)
@PluginDescriptor(
	name = "Vorkath Assistant",
	description = "Automatic Vorkath",
	tags = {"Vorkath"}
)
@Slf4j
public class VorkathHelperPlugin extends iScript {

	@Inject
	private VorkathHelperConfig config;

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
	private Game game;

	@Inject
	private CalculationUtils calc;

	private final List<WorldPoint> acidSpots;
	private List<WorldPoint> acidFreePath;
	private long sleepLength;
	private int timeout;
	private boolean dodgeFirebomb;
	private WorldPoint fireBallPoint;
	private WorldPoint safeTile;
	private List<WorldPoint> safeMeleeTiles;
	private boolean isAcid;
	private boolean isMinion;

	private final Set<Integer> DIAMOND_SET = Set.of(ItemID.DIAMOND_DRAGON_BOLTS_E, ItemID.DIAMOND_BOLTS_E);
	private final Set<Integer> RUBY_SET = Set.of(ItemID.RUBY_DRAGON_BOLTS_E, ItemID.RUBY_BOLTS_E);

	public VorkathHelperPlugin() {
		acidSpots = new ArrayList<>();
		acidFreePath = new ArrayList<>();
		safeMeleeTiles = new ArrayList<>();
		timeout = 0;
		dodgeFirebomb = false;
		fireBallPoint = null;
	}

	@Provides
	VorkathHelperConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(VorkathHelperConfig.class);
	}

	@Override
	protected void startUp() {
		log.info("Vorkath Helper startUp");
		safeMeleeTiles.clear();
		safeTile = null;
	}

	@Override
	protected void shutDown() {
		log.info("Vorkath Helper shutDown");
	}

	@Override
	protected void onStart() {
		log.info("Vorkath Helper started");
	}

	@Override
	protected void onStop() {
		log.info("Vorkath Helper stopped");
	}

	@Override
	protected void loop() {
		if(client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) return;

	}

	@Subscribe
	public void onGameTick(GameTick event){
		if(client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) return;

		final Player player = client.getLocalPlayer();
		final LocalPoint localLoc = player.getLocalLocation();
		final WorldPoint worldLoc = player.getWorldLocation();

		if(!isAtVorkath()){
			safeMeleeTiles.clear();
			return;
		}

		if(!isAcid()){
			isAcid = false;
			safeTile = null;
			acidFreePath.clear();
			acidSpots.clear();
		}

		Widget runOrb = client.getWidget(WidgetInfo.MINIMAP_RUN_ORB);

		createSafetiles();

		log.info(String.valueOf(getState()));

		switch(getState()){

			case TIMEOUT:
				timeout--;
				break;
			case TOGGLE_RUN:
				if(!playerUtils.isRunEnabled()) playerUtils.enableRun(runOrb.getBounds());
				break;
			case DODGE_BOMB:
				LocalPoint dodgeRight = new LocalPoint(localLoc.getX() + 256, localLoc.getY()); //Local point is 1/128th of a tile. 256 = 2 tiles
				LocalPoint dodgeLeft = new LocalPoint(localLoc.getX() - 256, localLoc.getY());
				LocalPoint dodgeReset = new LocalPoint(6208, 7872);

				if(dodgeFirebomb && !player.getWorldLocation().equals(fireBallPoint)){
					fireBallPoint = null;
					dodgeFirebomb = false;
					return;
				}
				if(localLoc.getY() > 7872){
					walkUtils.sceneWalk(dodgeReset, 0, sleepDelay());
					dodgeFirebomb = false;
					timeout+=2;
					return;
				}
				if (localLoc.getX() < 6208) {
					walkUtils.sceneWalk(dodgeRight, 0, sleepDelay());
				} else {
					walkUtils.sceneWalk(dodgeLeft, 0, sleepDelay());
				}
				break;
			case KILL_MINION:
				NPC iceMinion = npcUtils.findNearestNpc(NpcID.ZOMBIFIED_SPAWN_8063);

				if(player.getInteracting() != null && player.getInteracting().getName().equalsIgnoreCase("Vorkath")){
					walkUtils.sceneWalk(localLoc, 0, sleepDelay());
					return;
				}
				if(prayerUtils.isQuickPrayerActive() && config.enablePrayer()){
					prayerUtils.toggleQuickPrayer(false, sleepDelay());
					return;
				}
				if(iceMinion != null && player.getInteracting() == null) {
					attackMinion();
					timeout+=4;
				}
				break;
			case ACID_WALK:
				NPC vorkath = npcUtils.findNearestNpc(NpcID.VORKATH_8061);
				if(playerUtils.isRunEnabled() && runOrb != null && config.walkMethod().getId() != 3) {
					playerUtils.enableRun(runOrb.getBounds());
				}

				if(prayerUtils.isQuickPrayerActive() && (config.walkMethod().getId() != 2 || (config.walkMethod().getId() == 2 && player.isMoving()))){
					prayerUtils.toggleQuickPrayer(false, sleepDelay());
					//return;
				}


				if(config.walkMethod().getId() == 1) return;
				if(config.walkMethod().getId() == 2){
					if(!acidSpots.isEmpty()){
						if(acidFreePath.isEmpty()){
							calculateAcidFreePath(config.acidFreePathLength());
						}

						log.info("Acid free path size: " + acidFreePath.size());
						log.info("Config amount: " + config.acidFreePathLength());

						WorldPoint firstTile;
						WorldPoint lastTile;
						if(!acidFreePath.isEmpty()){
							firstTile = acidFreePath.get(0);
						}else{
							return;
						}

						if(acidFreePath.size() > config.acidFreePathLength()){
							lastTile = acidFreePath.get(config.acidFreePathLength());
						}else{
							lastTile = acidFreePath.get(acidFreePath.size() - 1);
						}

						log.info("First tile: " + firstTile);
						log.info("Last Tile: " + lastTile);
						log.info("Actual length: " + (firstTile.getX() != lastTile.getX() ? Math.abs(firstTile.getX() - lastTile.getX()) : Math.abs(firstTile.getY() - lastTile.getY())));

						if((!player.getWorldLocation().equals(firstTile) && !player.getWorldLocation().equals(lastTile) && !acidFreePath.contains(player.getWorldLocation()))
							|| player.getWorldLocation().equals(lastTile)){
							walkUtils.sceneWalk(firstTile, 0, sleepDelay());
							return;
						}
						if(player.getWorldLocation().equals(firstTile)){
							walkUtils.sceneWalk(lastTile, 0, sleepDelay());
							return;
						}
					}
				}
				else {
					Collections.sort(safeMeleeTiles, Comparator.comparingInt(o -> o.distanceTo(player.getWorldLocation())));

					if (safeTile == null) {
						for (int i = 0; i < safeMeleeTiles.size(); i++) {
							WorldPoint temp = safeMeleeTiles.get(i);
							WorldPoint temp2 = new WorldPoint(temp.getX(), temp.getY() - 1 , temp.getPlane());
							if (!acidSpots.contains(temp) && !acidSpots.contains(temp2)) {
								safeTile = temp2;
								break;
							}
						}
					}

					if(safeTile != null){
						if(player.getWorldLocation().equals(safeTile)){
							utils.doNpcActionMsTime(vorkath, MenuAction.NPC_SECOND_OPTION.getId(), 0);
						}else{
							LocalPoint lp = LocalPoint.fromWorld(client, safeTile);
							if(lp != null){
								walkUtils.walkTile(lp.getSceneX(), lp.getSceneY());
							}else{
								log.info("Local point is a null");
							}
						}
					}
				}

				break;
			case SWITCH_RUBY:
				equipRuby();
				break;
			case SWITCH_DIAMOND:
				equipDiamond();
				break;
			case RETALIATE:
				attackVorkath();
			case QUICKPRAYER_ON:
				if(!prayerUtils.isQuickPrayerActive() && prayerUtils.getPoints() > 0) prayerUtils.toggleQuickPrayer(true, sleepDelay());
				break;
			case QUICKPRAYER_OFF:
				if(prayerUtils.isQuickPrayerActive()) prayerUtils.toggleQuickPrayer(false, sleepDelay());
				break;
			case DEFAULT:
				break;
		}
	}

	@Subscribe
	private void onNpcSpawned(NpcSpawned event) {

	}

	@Subscribe
	private void onNpcDespawned(NpcDespawned event) {
		final NPC npc = event.getNpc();

		if (npc.getName() == null) return;

	}

	@Subscribe
	private void onAnimationChanged(AnimationChanged event) {
		if(client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
			return;

		final WorldPoint worldLoc = client.getLocalPlayer().getWorldLocation();
		final LocalPoint localLoc = LocalPoint.fromWorld(client, worldLoc);
		final Actor actor = event.getActor();
		final Player player = client.getLocalPlayer();

		if(actor.getAnimation() == 7889 || actor.getAnimation() == 7891){ //Minion hit animation
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

		final WorldPoint loc = player.getWorldLocation();

		final LocalPoint localLoc = LocalPoint.fromWorld(client, loc);

		if (projectile.getId() == VORKATH_BOMB_AOE && config.dodgeBomb()){
			fireBallPoint = player.getWorldLocation();
			dodgeFirebomb = true;
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

	private VorkathStates getState(){
		NPC vorkathAlive = npcUtils.findNearestNpc(NpcID.VORKATH_8061);
		Player player = client.getLocalPlayer();

		if(player == null) return null;

		if(timeout > 0 && (config.dodgeBomb() && !dodgeFirebomb))
			return VorkathStates.TIMEOUT;

		if(isAtVorkath() && !playerUtils.isRunEnabled() && !isAcid)
			return VorkathStates.TOGGLE_RUN;

		if(config.dodgeBomb() && dodgeFirebomb)
			return VorkathStates.DODGE_BOMB;

		if(config.killSpawn() && isMinion)
			return VorkathStates.KILL_MINION;

		if(config.walkMethod().getId() != 1 && isAcid)
			return VorkathStates.ACID_WALK;

		if(config.switchBolts() && vorkathAlive == null
				&& playerUtils.isItemEquipped(DIAMOND_SET) && invUtils.containsItem(RUBY_SET))
			return VorkathStates.SWITCH_RUBY;

		if(config.switchBolts() && vorkathAlive != null && !vorkathAlive.isDead()
				&& playerUtils.isItemEquipped(RUBY_SET)
				&& invUtils.containsItem(DIAMOND_SET)
				&& calculateHealth(vorkathAlive) < 260
				&& vorkathAlive.getAnimation() != 7960
				&& vorkathAlive.getAnimation() != 7957
				&& calculateHealth(vorkathAlive) > 0)
			return VorkathStates.SWITCH_DIAMOND;

		if(config.enablePrayer() && isAtVorkath() && prayerUtils.isQuickPrayerActive()
				&& prayerUtils.getPoints() > 0
				&& (isVorkathAsleep()
				|| (vorkathAlive != null && vorkathAlive.isDead())
				|| isMinion
				|| isAcid))
			return VorkathStates.QUICKPRAYER_OFF;

		if(config.enablePrayer() && prayerUtils.getPoints() > 0 && isAtVorkath() && !prayerUtils.isQuickPrayerActive()
				&& ((vorkathAlive != null
				&& !vorkathAlive.isDead()
				&& !isAcid
				&& !isMinion) || isWakingUp()))
			return VorkathStates.QUICKPRAYER_ON;

		if(config.fastRetaliate() && isAtVorkath() && player.getInteracting() == null
				&& vorkathAlive != null && !vorkathAlive.isDead()
				&& !isMinion
				&& !dodgeFirebomb
				&& !isAcid
				&& !isWakingUp())
			return VorkathStates.RETALIATE;

		return VorkathStates.DEFAULT;
	}

	private long sleepDelay() {
		sleepLength = calc.randomDelay(config.sleepWeightedDistribution(), config.sleepMin(), config.sleepMax(), config.sleepDeviation(), config.sleepTarget());
		return sleepLength;
	}

	private int tickDelay() {
		int tickLength = (int) calc.randomDelay(config.tickDelayWeightedDistribution(), config.tickDelayMin(), config.tickDelayMax(), config.tickDelayDeviation(), config.tickDelayTarget());
		return tickLength;
	}

	public void attackVorkath(){
		NPC vorkath = npcUtils.findNearestNpc(NpcID.VORKATH_8061);
		if(vorkath != null && (vorkath.getAnimation() != 7957 || vorkath.getAnimation() == 7949 || vorkath.isDead())) //Acid animation check
			utils.doNpcActionMsTime(vorkath, MenuAction.NPC_SECOND_OPTION.getId(), sleepDelay());
	}

	public void attackMinion(){
		NPC iceMinion = npcUtils.findNearestNpc(NpcID.ZOMBIFIED_SPAWN_8063);
		if(iceMinion != null && !iceMinion.isDead()) {
			LegacyMenuEntry entry = new LegacyMenuEntry("Cast", "", iceMinion.getIndex(), MenuAction.SPELL_CAST_ON_NPC.getId(), 0, 0, false);
			utils.oneClickCastSpell(WidgetInfo.SPELL_CRUMBLE_UNDEAD, entry, iceMinion.getConvexHull().getBounds(), 0);
		}
	}

	/*
	This method creates an initial row of tiles depending on the walk method.
	If the user uses the regular woox walk, it will create a row directly in front of vorkath which is then used to create a secondary walk tile behind
	If the user uses the ranged woox walk, it will create a row at the entrance of the instance that will then be used to create a secondary walk tile in front
	 */
	public void createSafetiles(){
		if(isAtVorkath()){
			if(safeMeleeTiles.size() > 8) safeMeleeTiles.clear();
			LocalPoint southWest = config.walkMethod().getId() == 3 ? new LocalPoint(5824, 7872) : new LocalPoint(5824, 7104);
			WorldPoint base = WorldPoint.fromLocal(client, southWest);
			for(int i = 0; i < 7; i++){
				safeMeleeTiles.add(new WorldPoint(base.getX() + i, base.getY(), base.getPlane()));
			}
		}else if(!isAtVorkath() && !safeMeleeTiles.isEmpty()){
			safeMeleeTiles.clear();
		}
	}

	public boolean isAtVorkath(){
		NPC vorkath = npcUtils.findNearestNpc("Vorkath");
		return client.isInInstancedRegion() && vorkath != null;
	}

	public boolean isVorkathAsleep(){
		NPC vorkathAsleep = npcUtils.findNearestNpc(NpcID.VORKATH_8059);
		return isAtVorkath() && vorkathAsleep != null;
	}

	public boolean isWakingUp(){
		NPC vorkathWaking = npcUtils.findNearestNpc(NpcID.VORKATH_8058);
		return isAtVorkath() && vorkathWaking != null;
	}

	private void addAcidSpot(WorldPoint worldPoint) {
		if (!acidSpots.contains(worldPoint))
			acidSpots.add(worldPoint);
	}

	private void calculateAcidFreePath(int size) {

		Player player = client.getLocalPlayer();
		NPC vorkath = npcUtils.findNearestNpc(NpcID.VORKATH_8061);

		if(player == null || vorkath == null) return;

		acidFreePath.clear();

		int[][][] array = { { { 0, 1 }, { 0, -1 } }, { { 1, 0 }, { -1, 0 } } };
		ArrayList<WorldPoint> bestPath = new ArrayList<>();
		double bestClicksRequired = 99.0D;

		WorldPoint worldLocation = player.getWorldLocation();
		WorldPoint worldLocation2 = vorkath.getWorldLocation();

		int n2 = worldLocation2.getX() + 14;
		int n3 = worldLocation2.getX() - 8;
		int n4 = worldLocation2.getY() - 1;
		int n5 = worldLocation2.getY() - 8;
		for (int i = -1; i < 2; i++) {
			for (int j = -1; j < 2; j++) {
				WorldPoint worldPoint = new WorldPoint(
						worldLocation.getX() + i,
						worldLocation.getY() + j,
						worldLocation.getPlane());
				if (!acidSpots.contains(worldPoint)
						&& worldPoint.getY() >= n5
						&& worldPoint.getY() <= n4)
					for (int l = 0; l < 2; l++) {
						double clicksRequired;
						if ((clicksRequired = (Math.abs(i) + Math.abs(j))) < 2.0D)
							clicksRequired += (Math.abs(j * array[l][0][0]) + Math.abs(i * array[l][0][1]));
						if (l == 0)
							clicksRequired += 0.5D;
						ArrayList<WorldPoint> currentPath;
						(currentPath = new ArrayList<>()).add(worldPoint);
						for (int n7 = 1; n7 < 25; n7++) {
							WorldPoint worldPoint2 = new WorldPoint(
									worldPoint.getX() + n7 * array[l][0][0],
									worldPoint.getY() + n7 * array[l][0][1],
									worldPoint.getPlane());

							if (acidSpots.contains(worldPoint2)
									|| worldPoint2.getY() < n5
									|| worldPoint2.getY() > n4
									|| worldPoint2.getX() < n3
									|| worldPoint2.getX() > n2)
								break;
							currentPath.add(worldPoint2);
						}
						for (int n8 = 1; n8 < 25; n8++) {
							WorldPoint worldPoint3 = new WorldPoint(
									worldPoint.getX() + n8 * array[l][1][0],
									worldPoint.getY() + n8 * array[l][1][1],
									worldPoint.getPlane());

							if (acidSpots.contains(worldPoint3)
									|| worldPoint3.getY() < n5
									|| worldPoint3.getY() > n4
									|| worldPoint3.getX() < n3
									|| worldPoint3.getX() > n2)
								break;
							currentPath.add(worldPoint3);
						}

						if ((currentPath.size() >= size && clicksRequired <= bestClicksRequired)
								|| (clicksRequired == bestClicksRequired && currentPath.size() > bestPath.size())) {
							bestPath = currentPath;
							bestClicksRequired = clicksRequired;
						}
					}
			}
		}

		if (bestClicksRequired != 99.0D)
			acidFreePath = bestPath;
	}

	public void equipDiamond() {
		if (!playerUtils.isItemEquipped(DIAMOND_SET) && invUtils.containsItem(DIAMOND_SET)) {

			WidgetItem diamondBolts = invUtils.getWidgetItem(DIAMOND_SET);

			if (diamondBolts != null)
				utils.doItemActionMsTime(diamondBolts, MenuAction.ITEM_SECOND_OPTION.getId(), 9764864, sleepDelay());
		}
	}

	public void equipRuby() {
		if (!playerUtils.isItemEquipped(RUBY_SET) && invUtils.containsItem(RUBY_SET)) {

			WidgetItem rubyBolts = invUtils.getWidgetItem(RUBY_SET);

			if (rubyBolts != null)
				utils.doItemActionMsTime(rubyBolts, MenuAction.ITEM_SECOND_OPTION.getId(), 9764864, sleepDelay());

		}
	}

	private int calculateHealth(NPC target) {
		// Based on OpponentInfoOverlay HP calculation & taken from the default slayer plugin
		if (target == null || target.getName() == null)
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

	public boolean isAcid(){
		GameObject pool = objectUtils.findNearestGameObject(ACID_POOL_32000);
		NPC vorkath = npcUtils.findNearestNpc(NpcID.VORKATH_8061);
		return pool != null || (vorkath != null && vorkath.getAnimation() == 7957);
	}
}
