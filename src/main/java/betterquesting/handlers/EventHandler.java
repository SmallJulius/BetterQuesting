package betterquesting.handlers;

import java.io.File;
import java.util.Date;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.UserListBansEntry;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IIcon;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.event.world.WorldEvent;
import org.apache.logging.log4j.Level;
import betterquesting.api.client.gui.misc.INeedsRefresh;
import betterquesting.api.enums.EnumSaveType;
import betterquesting.api.events.DatabaseEvent;
import betterquesting.api.party.IParty;
import betterquesting.api.quests.IQuest;
import betterquesting.api.quests.properties.NativeProps;
import betterquesting.api.utils.JsonHelper;
import betterquesting.api.utils.placeholders.FluidPlaceholder;
import betterquesting.client.BQ_Keybindings;
import betterquesting.client.gui.GuiHome;
import betterquesting.core.BQ_Settings;
import betterquesting.core.BetterQuesting;
import betterquesting.database.QuestDatabase;
import betterquesting.database.QuestLineDatabase;
import betterquesting.legacy.ILegacyLoader;
import betterquesting.legacy.LegacyLoaderRegistry;
import betterquesting.lives.LifeDatabase;
import betterquesting.network.PacketSender;
import betterquesting.party.PartyManager;
import betterquesting.quests.NameCache;
import betterquesting.quests.QuestSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerRespawnEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Event handling for standard quests and core BetterQuesting functionality
 */
public class EventHandler
{
	@SubscribeEvent
	@SideOnly(Side.CLIENT)
	public void onKey(InputEvent.KeyInputEvent event)
	{
		Minecraft mc = Minecraft.getMinecraft();
		
		if(BQ_Keybindings.openQuests.isPressed())
		{
			mc.displayGuiScreen(new GuiHome(mc.currentScreen));
		}
	}
	
	@SubscribeEvent
	public void onLivingUpdate(LivingUpdateEvent event)
	{
		if(event.entityLiving.worldObj.isRemote)
		{
			return;
		}
		
		if(event.entityLiving instanceof EntityPlayer)
		{
			for(IQuest quest : QuestDatabase.INSTANCE.getAllValues())
			{
				quest.update((EntityPlayer)event.entityLiving);
			}
		}
	}
	
	@SubscribeEvent
	public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event)
	{
		if(event.modID.equals(BetterQuesting.MODID))
		{
			ConfigHandler.config.save();
			ConfigHandler.initConfigs();
		}
	}
	
	@SubscribeEvent
	public void onWorldSave(WorldEvent.Save event)
	{
		if(!event.world.isRemote && BQ_Settings.curWorldDir != null && event.world.provider.dimensionId == 0)
		{
			// === CONFIG ===
			
			JsonObject jsonCon = new JsonObject();
			
			jsonCon.add("questSettings", QuestSettings.INSTANCE.writeToJson(new JsonObject(), EnumSaveType.CONFIG));
			jsonCon.add("questDatabase", QuestDatabase.INSTANCE.writeToJson(new JsonArray(), EnumSaveType.CONFIG));
			jsonCon.add("questLines", QuestLineDatabase.INSTANCE.writeToJson(new JsonArray(), EnumSaveType.CONFIG));
			
			jsonCon.addProperty("format", BetterQuesting.FORMAT);
			
			JsonHelper.WriteToFile(new File(BQ_Settings.curWorldDir, "QuestDatabase.json"), jsonCon);
			
			// === PROGRESS ===
			
			JsonObject jsonProg = new JsonObject();
			
			jsonProg.add("questProgress", QuestDatabase.INSTANCE.writeToJson(new JsonArray(), EnumSaveType.PROGRESS));
			
			JsonHelper.WriteToFile(new File(BQ_Settings.curWorldDir, "QuestProgress.json"), jsonProg);
			
			// === PARTIES ===
			
			JsonObject jsonP = new JsonObject();
			
			jsonP.add("parties", PartyManager.INSTANCE.writeToJson(new JsonArray(), EnumSaveType.CONFIG));
			
			JsonHelper.WriteToFile(new File(BQ_Settings.curWorldDir, "QuestingParties.json"), jsonP);
			
			// === NAMES ===
			
			JsonObject jsonN = new JsonObject();
			
			jsonN.add("nameCache", NameCache.INSTANCE.writeToJson(new JsonArray(), EnumSaveType.CONFIG));
			
			JsonHelper.WriteToFile(new File(BQ_Settings.curWorldDir, "NameCache.json"), jsonN);
		    
		    MinecraftForge.EVENT_BUS.post(new DatabaseEvent.Save());
		}
	}
	
	@SubscribeEvent
	public void onWorldUnload(WorldEvent.Unload event)
	{
		if(!event.world.isRemote && !MinecraftServer.getServer().isServerRunning())
		{
			BQ_Settings.curWorldDir = null;
			
			QuestSettings.INSTANCE.reset();
			QuestDatabase.INSTANCE.reset();
			QuestLineDatabase.INSTANCE.reset();
			LifeDatabase.INSTANCE.reset();
			NameCache.INSTANCE.reset();
		}
	}
	
	@SubscribeEvent
	public void onWorldLoad(WorldEvent.Load event)
	{
		if(event.world.isRemote || BQ_Settings.curWorldDir != null)
		{
			return;
		}
		
		QuestSettings.INSTANCE.reset();
		QuestDatabase.INSTANCE.reset();
		QuestLineDatabase.INSTANCE.reset();
		LifeDatabase.INSTANCE.reset();
		NameCache.INSTANCE.reset();
		
		MinecraftServer server = MinecraftServer.getServer();
		
		File readDir;
		
		if(BetterQuesting.proxy.isClient())
		{
			BQ_Settings.curWorldDir = server.getFile("saves/" + server.getFolderName() + "/betterquesting");
			readDir = server.getFile("saves/" + server.getFolderName());
		} else
		{
			BQ_Settings.curWorldDir = server.getFile(server.getFolderName() + "/betterquesting");
			readDir = server.getFile(server.getFolderName());
		}
    	
		// Workaround for old files
		boolean rename = false;
		File legFile = new File(readDir, "QuestDatabase.json");
		if(legFile.exists())
		{
			rename = true;
		} else
		{
			readDir = BQ_Settings.curWorldDir;
		}
		
		// === CONFIG ===
		
    	File f1 = new File(readDir, "QuestDatabase.json");
		JsonObject j1 = new JsonObject();
		
		if(f1.exists())
		{
			j1 = JsonHelper.ReadFromFile(f1);
			
			if(rename)
			{
				JsonHelper.CopyPaste(f1, new File(readDir, "QuestDatabase_Legacy.json"));
				f1.delete();
			}
		} else
		{
			f1 = new File(BQ_Settings.defaultDir, "DefaultQuests.json");
			
			if(f1.exists())
			{
				j1 = JsonHelper.ReadFromFile(f1);
			}
		}
		
		String fVer = JsonHelper.GetString(j1, "format", "0.0.0");
		
		ILegacyLoader loader = LegacyLoaderRegistry.getLoader(fVer);
		
		if(loader == null)
		{
			QuestSettings.INSTANCE.readFromJson(JsonHelper.GetObject(j1, "questSettings"), EnumSaveType.CONFIG);
			QuestDatabase.INSTANCE.readFromJson(JsonHelper.GetArray(j1, "questDatabase"), EnumSaveType.CONFIG);
			QuestLineDatabase.INSTANCE.readFromJson(JsonHelper.GetArray(j1, "questLines"), EnumSaveType.CONFIG);
		} else
		{
			loader.readFromJson(j1, EnumSaveType.CONFIG);
		}
    	
		// === PROGRESS ===
		
    	File f2 = new File(readDir, "QuestProgress.json");
		JsonObject j2 = new JsonObject();
		
		if(f2.exists())
		{
			j2 = JsonHelper.ReadFromFile(f2);
			
			if(rename)
			{
				JsonHelper.CopyPaste(f2, new File(readDir, "QuestDatabase_Legacy.json"));
				f2.delete();
			}
		}
		
		if(loader == null)
		{
			QuestDatabase.INSTANCE.readFromJson(JsonHelper.GetArray(j2, "questProgress"), EnumSaveType.PROGRESS);
		} else
		{
			loader.readFromJson(j2, EnumSaveType.PROGRESS);
		}
		
		// === PARTIES ===
		
	    File f3 = new File(BQ_Settings.curWorldDir, "QuestingParties.json");
	    JsonObject j3 = new JsonObject();
	    
	    if(f3.exists())
	    {
	    	j3 = JsonHelper.ReadFromFile(f3);
	    }
	    
	    PartyManager.INSTANCE.readFromJson(JsonHelper.GetArray(j3, "parties"), EnumSaveType.CONFIG);
	    
	    // === NAMES ===
	    
	    File f4 = new File(BQ_Settings.curWorldDir, "NameCache.json");
	    JsonObject j4 = new JsonObject();
	    
	    if(f4.exists())
	    {
	    	j4 = JsonHelper.ReadFromFile(f4);
	    }
	    
	    NameCache.INSTANCE.readFromJson(JsonHelper.GetArray(j4, "nameCache"), EnumSaveType.CONFIG);
	    
	    BetterQuesting.logger.log(Level.INFO, "Loaded " + QuestDatabase.INSTANCE.size() + " quests");
	    BetterQuesting.logger.log(Level.INFO, "Loaded " + QuestLineDatabase.INSTANCE.size() + " quest lines");
	    BetterQuesting.logger.log(Level.INFO, "Loaded " + PartyManager.INSTANCE.size() + " parties");
	    BetterQuesting.logger.log(Level.INFO, "Loaded " + NameCache.INSTANCE.size() + " names");
	    
	    MinecraftForge.EVENT_BUS.post(new DatabaseEvent.Load());
	}
	
	@SubscribeEvent
	public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event)
	{
		if(!event.player.worldObj.isRemote && event.player instanceof EntityPlayerMP)
		{
			EntityPlayerMP mpPlayer = (EntityPlayerMP)event.player;
			
			NameCache.INSTANCE.updateNames(MinecraftServer.getServer());
			
			PacketSender.INSTANCE.sendToPlayer(QuestDatabase.INSTANCE.getSyncPacket(), mpPlayer);
			PacketSender.INSTANCE.sendToPlayer(QuestLineDatabase.INSTANCE.getSyncPacket(), mpPlayer);
			PacketSender.INSTANCE.sendToPlayer(LifeDatabase.INSTANCE.getSyncPacket(), mpPlayer);
			PacketSender.INSTANCE.sendToPlayer(PartyManager.INSTANCE.getSyncPacket(), mpPlayer);
		}
	}
	
	@SubscribeEvent
	public void onPlayerRespawn(PlayerRespawnEvent event)
	{
		if(QuestSettings.INSTANCE.getProperty(NativeProps.HARDCORE) && event.player instanceof EntityPlayerMP && !((EntityPlayerMP)event.player).playerConqueredTheEnd)
		{
			EntityPlayerMP mpPlayer = (EntityPlayerMP)event.player;
			
			IParty party = PartyManager.INSTANCE.getUserParty(mpPlayer.getGameProfile().getId());
			int lives = (party == null || !party.getShareLives())? LifeDatabase.INSTANCE.getLives(mpPlayer.getGameProfile().getId()) : LifeDatabase.INSTANCE.getLives(party);
			
			if(lives <= 0)
			{
				MinecraftServer server = MinecraftServer.getServer();
				
				if(server == null)
				{
					return;
				}
	            
	            if (server.isSinglePlayer() && mpPlayer.getCommandSenderName().equals(server.getServerOwner()))
                {
                    mpPlayer.playerNetServerHandler.kickPlayerFromServer("You have died. Game over, man, it\'s game over!");
                    server.deleteWorldAndStopServer();
                }
                else
                {
                    UserListBansEntry userlistbansentry = new UserListBansEntry(mpPlayer.getGameProfile(), (Date)null, "(You just lost the game)", (Date)null, "Death in Hardcore");
                    server.getConfigurationManager().func_152608_h().func_152687_a(userlistbansentry);
                    mpPlayer.playerNetServerHandler.kickPlayerFromServer("You have died. Game over, man, it\'s game over!");
                }
			} else
			{
				if(lives == 1)
				{
					mpPlayer.addChatComponentMessage(new ChatComponentText("This is your last life!"));
				} else
				{
					mpPlayer.addChatComponentMessage(new ChatComponentText(lives + " lives remaining!"));
				}
			}
		}
	}
	
	@SubscribeEvent
	public void onLivingDeath(LivingDeathEvent event)
	{
		if(event.entityLiving.worldObj.isRemote || !QuestSettings.INSTANCE.getProperty(NativeProps.HARDCORE))
		{
			return;
		}
		
		if(event.entityLiving instanceof EntityPlayer)
		{
			UUID uuid = ((EntityPlayer)event.entityLiving).getGameProfile().getId();
			IParty party = PartyManager.INSTANCE.getUserParty(uuid);
			
			if(party == null || !party.getShareLives())
			{
				int lives = LifeDatabase.INSTANCE.getLives(((EntityPlayer)event.entityLiving).getGameProfile().getId());
				LifeDatabase.INSTANCE.setLives(uuid, lives - 1);
			} else
			{
				int lives = LifeDatabase.INSTANCE.getLives(party);
				LifeDatabase.INSTANCE.setLives(party, lives - 1);
			}
		}
	}
	
	@SubscribeEvent
	@SideOnly(Side.CLIENT)
	public void onTextureStitch(TextureStitchEvent.Pre event)
	{
		if(event.map.getTextureType() == 0)
		{
			IIcon icon = event.map.registerIcon("betterquesting:fluid_placeholder");
			FluidPlaceholder.fluidPlaceholder.setIcons(icon);
		}
	}
	
	@SubscribeEvent
	@SideOnly(Side.CLIENT)
	public void onDataUpdated(DatabaseEvent.Update event)
	{
		GuiScreen screen = Minecraft.getMinecraft().currentScreen;
		
		if(screen instanceof INeedsRefresh)
		{
			((INeedsRefresh)screen).refreshGui();
		}
	}
}
