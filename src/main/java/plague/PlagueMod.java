package plague;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import arc.*;
import arc.graphics.Color;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.core.NetServer.*;
import mindustry.entities.traits.BuilderTrait;
import mindustry.entities.type.*;
import mindustry.game.EventType;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.plugin.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock;

import static arc.util.Log.info;
import static java.lang.Math.abs;
import static mindustry.Vars.*;

public class PlagueMod extends Plugin{

    public int teams = 0;
    public int infected = 0;
    public int survivors = 0;

	// TPS = 40
    //in seconds
    public static final float spawnDelay = 60 * 4;
    //health requirement needed to capture a hex; no longer used
    public static final float healthRequirement = 3500;
    //item requirement to captured a hex
    public static final int itemRequirement = 1500;

    public static final int messageTime = 1;

    //in ticks: 60 minutes: 60 * 60 * 60
    private int roundTime = 60 * 60 * 60;
    //in ticks: 30 seconds
    private final static int infectTime = 60 * 30;

    private int lastMin;

    private final Rules rules = new Rules();
    private Rules noTurretRules = new Rules();
    private Rules noMechRules = new Rules();
    private Interval interval = new Interval(5);

    private boolean restarting = false, registered = false;

    private Array<Array<ItemStack>> loadouts = new Array<>(4);

    private double counter = 0f;

  	private String[] announcements = {"Join the discord at: [purple]https://discord.gg/GEnYcSv", "Rank up to earn [darkgray]common[white] trails or donate to get [purple]epic[white] ones!", "The top 5 point scoring players at the end of the month will get a [pink]unique [white]trail!", "[gold]Spectres[white] do [scarlet]4x [white]damage and have [scarlet]3x [white]health!", "Use [accent]/hub[white] to return to the hub!"};
  	private int announcementIndex = 0;

  	private int lives = 1;
    private Map<String, List<Integer>> playerShowBuild = new HashMap<String, List<Integer>>();
    
    private Map<String, Boolean> core_count = new HashMap<String, Boolean>();


    @Override
    public void init(){


    	loadouts.add(ItemStack.list(Items.copper, 1000, Items.lead, 1000, Items.graphite, 200, Items.metaglass, 200, Items.silicon, 200));
        rules.pvp = !true;
        rules.tags.put("plague", "true");
        rules.loadout = loadouts.get(0);
        rules.buildCostMultiplier = 1f;
        rules.buildSpeedMultiplier = 2;
        rules.blockHealthMultiplier = 1f;
        rules.unitBuildSpeedMultiplier = 1f;
        rules.playerDamageMultiplier = 0f;
        //rules.enemyCoreBuildRadius = 100 * tilesize;
        rules.unitDamageMultiplier = 1f;
        rules.playerHealthMultiplier = 0.5f;
        rules.canGameOver = false;

        noMechRules = rules.copy();
        noMechRules.bannedBlocks.addAll(Blocks.commandCenter, Blocks.wraithFactory, Blocks.ghoulFactory, Blocks.revenantFactory, Blocks.daggerFactory,
                Blocks.crawlerFactory, Blocks.titanFactory, Blocks.fortressFactory);

        noTurretRules = rules.copy();
        noTurretRules.bannedBlocks.addAll(Blocks.duo, Blocks.scatter, Blocks.scorch, Blocks.wave, Blocks.lancer, Blocks.arc, Blocks.swarmer, Blocks.salvo,
                Blocks.fuse, Blocks.cyclone, Blocks.spectre, Blocks.meltdown, Blocks.hail, Blocks.ripple, Blocks.shockMine);

        Core.settings.putSave("playerlimit", 0);

        // Create the two cores at spawn:
        //


        TeamAssigner prev = netServer.assigner;
        netServer.assigner = (player, players) -> {
            Array<Player> arr = Array.with(players);

            if(counter < infectTime){
                survivors ++;
                return Team.green;
            }else{
                infected ++;
                return Team.crux;
            }
        };
        Events.on(EventType.Trigger.update, ()-> {
            if(survivors < 1 && counter > infectTime || counter > roundTime){
                endGame();
            }

            for (Player player : playerGroup.all()) {
                if (player.getTeam() != Team.derelict && player.getTeam().cores().isEmpty() && counter > infectTime) {
                    infected ++;
                    survivors --;
                    killTiles(player.getTeam(), player);
                    player.setTeam(Team.crux);
                    player.kill();
                    Call.onSetRules(player.con, noTurretRules);
                    Call.sendMessage("[accent]" + player.name + "[white] was [red]infected[white]!");
                }
            }
            if(counter > infectTime && infected == 0 && playerGroup.all().size > 0){
                infected ++;
                survivors --;
                Player player = playerGroup.all().random();
                killTiles(player.getTeam(), player);
                player.setTeam(Team.crux);
                player.kill();
                Call.onSetRules(player.con, noTurretRules);
                Call.sendMessage("[accent]" + player.name + "[white] was [red]infected[white]!");
            }


            counter += Time.delta();
            lastMin = (int) Math.ceil((roundTime - counter) / 60 / 60);
        });

        netServer.admins.addActionFilter((action) -> {
            if(cartesianDistance(action.tile.x, action.tile.y, 255, 255) < 100) {
                return false;
            }
            return true;
        });

        Events.on(EventType.PlayerJoin.class, event -> {
            Tile tile = world.tile(255, 255);
            Call.onUnitRespawn(tile, event.player);
        });

        Events.on(EventType.PlayerLeave.class, event -> {
            if(event.player.getTeam() != Team.crux){
                killTiles(event.player.getTeam(), event.player);
                survivors --;
            }else{
                infected --;
            }
        });

        Events.on(EventType.BuildSelectEvent.class, event ->{
            if(event.team == Team.green){
                event.tile.removeNet();
                if(Build.validPlace(event.team, event.tile.x, event.tile.y, Blocks.spectre, 0)){ // Use spectre in place of core, as core always returns false
                    Player player = playerGroup.getByID(event.builder.getID());
                    player.setTeam(Team.all()[teams+6]);
                    teams ++;
                    event.tile.setNet(Blocks.coreFoundation, event.builder.getTeam(), 0);
                    for(ItemStack stack : state.rules.loadout){
                        Call.transferItemTo(stack.item, stack.amount, event.tile.drawx(), event.tile.drawy(), event.tile);
                    }
                    Call.onSetRules(player.con, noMechRules);
                }
            }

        });

    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("plague", "Begin hosting with the Plague gamemode.", args -> {
            if(!state.is(State.menu)){
                Log.err("Stop the server first.");
                return;
            }

            logic.reset();
            Log.info("Generating map...");
            PlagueGenerator generator = new PlagueGenerator();
            world.loadGenerator(generator);
            info("Map generated.");
            state.rules = rules.copy();
            logic.play();
            netServer.openServer();


            Tile tile = world.tile(255,255);
            //tile.setNet(Blocks.coreFoundation, Team.green, 0);
            tile = world.tile(255,255);
            tile.setNet(Blocks.coreFoundation, Team.crux, 0);
            // tile.setNet(Blocks.coreFoundation, Team.purple, 0);
        });

        handler.register("countdown", "Get the hexed restart countdown.", args -> {
            Log.info("Time until round ends: &lc{0} minutes", (int)(roundTime - counter) / 60 / 60);
        });

        handler.register("end", "End the game.", args -> endGame());

        handler.register("r", "Restart the server.", args -> System.exit(2));

    }

    @Override
    public void registerClientCommands(CommandHandler handler){
        if(registered) return;
        registered = true;

        handler.<Player>register("hub", "Connect to the AA hub server", (args, player) -> {
            Call.onConnect(player.con, "aamindustry.play.ai", 6567);
        });

        handler.<Player>register("spectate", "Enter spectator mode. This destroys your base.", (args, player) -> {
             if(player.getTeam() == Team.derelict){
                 player.sendMessage("[scarlet]You're already spectating.");
             }else{
                 killTiles(player.getTeam(), player);
                 player.kill();
                 player.setTeam(Team.derelict);
             }
        });

        handler.<Player>register("spec", "Alias for spectate", (args, player) -> {
            if(player.getTeam() == Team.derelict){
                player.sendMessage("[scarlet]You're already spectating.");
            }else{
                killTiles(player.getTeam(), player);
                player.kill();
                player.setTeam(Team.derelict);
            }
        });

        handler.<Player>register("time", "Display the time left", (args, player) -> {
            player.sendMessage(String.valueOf("[scarlet]" + lastMin + "[lightgray] mins. remaining\n"));
        });

        handler.<Player>register("enemies", "List enemies", (args, player) -> {
            player.sendMessage(String.valueOf(player.getTeam().enemies()));
        });

    }


    void endGame(){
        if(restarting) return;

        boolean survived = false;
        for(Player player: playerGroup.all()){
            if(player.getTeam() != Team.crux && player.getTeam() != Team.derelict){
                survived = true;
                break;
            }
        }

        for(Player player: playerGroup.all()){
            if(survived){
                Call.onInfoMessage(player.con, "[accent]--ROUND OVER--\n\n[green]Survivors[lightgray] win!");
            }else{
                Call.onInfoMessage(player.con, "[accent]--ROUND OVER--\n\n[red]Plague[lightgray] wins!");
            }
        }
        restarting = true;
        Log.info("&ly--SERVER RESTARTING--");

        Time.runTask(60f * 10f, () -> {
            for(Player player : playerGroup.all()) {
                Call.onConnect(player.con, "aamindustry.play.ai", 6567);
                player.con.close();
            }
            // I shouldn't need this, all players should be gone since I connected them to hub
            // netServer.kickAll(KickReason.serverRestarting);
            Time.runTask(5f, () -> System.exit(2));
        });
    }


    void killTiles(Team team, Player player){
        for(int x = 0; x < world.width(); x++){
            for(int y = 0; y < world.height(); y++){
                Tile tile = world.tile(x, y);
                if(tile.entity != null && tile.getTeam() == team){
                    Time.run(Mathf.random(60f * 6), tile.entity::kill);
                }
            }
        }
    }

    public String filterColor(String s, String prefix){
        return prefix + Strings.stripColors(s);
    }

    public boolean active(){
        return state.rules.tags.getBool("plague") && !state.is(State.menu);
    }

    private float cartesianDistance(float x, float y, float cx, float cy){
        return (float) Math.sqrt(Math.pow(x - cx, 2) + Math.pow(y - cy, 2) );
    }
}