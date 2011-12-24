package org.dynmap.essentials;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Wolf;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Event.Type;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;
import org.dynmap.markers.Marker;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import com.earth2me.essentials.UserMap;
import com.earth2me.essentials.Warps;

public class DynmapEssentialsPlugin extends JavaPlugin {
    private static final Logger log = Logger.getLogger("Minecraft");
    private static final String LOG_PREFIX = "[Dynmap-Essentials] ";

    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    Essentials essentials;
    Warps warps;
    UserMap users;
    
    FileConfiguration cfg;
    
    private abstract class Layer {
        MarkerSet set;
        MarkerIcon deficon;
        String labelfmt;
        Set<String> visible;
        Set<String> hidden;
        Map<String, Marker> markers = new HashMap<String, Marker>();
        
        public Layer(String id, FileConfiguration cfg, String deflabel, String deficon, String deflabelfmt) {
            set = markerapi.getMarkerSet("commandbook." + id);
            if(set == null)
                set = markerapi.createMarkerSet("commandbook."+id, cfg.getString("layer."+id+".name", deflabel), null, false);
            else
                set.setMarkerSetLabel(cfg.getString("layer."+id+".name", deflabel));
            if(set == null) {
                severe("Error creating " + deflabel + " marker set");
                return;
            }
            set.setLayerPriority(cfg.getInt("layer."+id+".layerprio", 10));
            set.setHideByDefault(cfg.getBoolean("layer."+id+".hidebydefault", false));
            int minzoom = cfg.getInt("layer."+id+".minzoom", 0);
            if(minzoom > 0) /* Don't call if non-default - lets us work with pre-0.28 dynmap */
                set.setMinZoom(minzoom);
            String icon = cfg.getString("layer."+id+".deficon", deficon);
            this.deficon = markerapi.getMarkerIcon(icon);
            if(this.deficon == null) {
                info("Unable to load default icon '" + icon + "' - using default '"+deficon+"'");
                this.deficon = markerapi.getMarkerIcon(deficon);
            }
            labelfmt = cfg.getString("layer."+id+".labelfmt", deflabelfmt);
            List<String> lst = cfg.getStringList("layer."+id+".visiblemarkers");
            if(lst != null)
                visible = new HashSet<String>(lst);
            lst = cfg.getStringList("layer."+id+".hiddenmarkers");
            if(lst != null)
                hidden = new HashSet<String>(lst);
        }
        
        void cleanup() {
            if(set != null) {
                set.deleteMarkerSet();
                set = null;
            }
            markers.clear();
        }
        
        boolean isVisible(String id, String wname) {
            if((visible != null) && (visible.isEmpty() == false)) {
                if((visible.contains(id) == false) && (visible.contains("world:" + wname) == false))
                    return false;
            }
            if((hidden != null) && (hidden.isEmpty() == false)) {
                if(hidden.contains(id) || hidden.contains("world:" + wname))
                    return false;
            }
            return true;
        }
        
        void updateMarkerSet() {
            Map<String, Marker> newmap = new HashMap<String, Marker>(); /* Build new map */
            
            Map<String,Location> marks = getMarkers();
            for(String name: marks.keySet()) {
                Location loc = marks.get(name);
                
                String wname = loc.getWorld().getName();
                /* Skip if not visible */
                if(isVisible(name, wname) == false)
                    continue;
                /* Get location */
                String id = wname + "/" + name;

                String label = labelfmt.replaceAll("%name%", name);
                    
                /* See if we already have marker */
                Marker m = markers.remove(id);
                if(m == null) { /* Not found?  Need new one */
                    m = set.createMarker(id, label, wname, loc.getX(), loc.getY(), loc.getZ(), deficon, false);
                }
                else {  /* Else, update position if needed */
                    m.setLocation(wname, loc.getX(), loc.getY(), loc.getZ());
                    m.setLabel(label);
                    m.setMarkerIcon(deficon);
                }
                newmap.put(id, m);    /* Add to new map */
            }
            /* Now, review old map - anything left is gone */
            for(Marker oldm : markers.values()) {
                oldm.deleteMarker();
            }
            /* And replace with new map */
            markers.clear();
            markers = newmap;
        }
        /* Get current markers, by ID with location */
        public abstract Map<String,Location> getMarkers();
    }

    private class WarpsLayer extends Layer {

        public WarpsLayer(FileConfiguration cfg) {
            super("warps", cfg, "Warps", "portal", "[%name%]");
        }
        /* Get current markers, by ID with location */
        public Map<String,Location> getMarkers() {
            HashMap<String,Location> map = new HashMap<String,Location>();
            if(warps != null) {
                Collection<String> wn = warps.getWarpNames();
                for(String n: wn) {
                    Location loc;
                    try {
                        loc = warps.getWarp(n);
                        if(loc != null) {
                            map.put(n, loc);
                        }
                    } catch (Exception x) {}
                }
            }
            return map;
        }
    }
    
    private class HomesLayer extends Layer {
        public HomesLayer(FileConfiguration cfg) {
            super("homes", cfg, "Homes", "house", "%name%(home)");
        }
        /* Get current markers, by ID with location */
        public Map<String,Location> getMarkers() {
            HashMap<String,Location> map = new HashMap<String,Location>();
            if(users != null) {
                Set<String> uids = users.getAllUniqueUsers();
                for(String uid: uids) {
                    User u = users.getUser(uid);
                    if(u == null) continue;
                    List<String> homes = u.getHomes();
                    if(homes == null) continue;
                    for(String home : homes) {
                        try {
                            Location loc = u.getHome(home);
                            if(loc != null) {
                                if(home.equals("home"))
                                    map.put(uid, loc);
                                else
                                    map.put(uid+":"+home, loc);
                            }
                        } catch (Exception x) {}
                    }
                }
            }
            return map;
        }
    }
    
    /* Homes layer settings */
    private Layer homelayer;
    
    /* Warps layer settings */
    private Layer warplayer;
    
    long updperiod;
    boolean stop;
    
    public static void info(String msg) {
        log.log(Level.INFO, LOG_PREFIX + msg);
    }
    public static void severe(String msg) {
        log.log(Level.SEVERE, LOG_PREFIX + msg);
    }

    private class MarkerUpdate implements Runnable {
        public void run() {
            if(!stop)
                updateMarkers();
        }
    }
    
    /* Update mob population and position */
    private void updateMarkers() {
        if(users != null) {
            homelayer.updateMarkerSet();
        }
        if(warps != null) {
            warplayer.updateMarkerSet();
        }
        getServer().getScheduler().scheduleSyncDelayedTask(this, new MarkerUpdate(), updperiod);
    }
    
    private class OurServerListener extends ServerListener {
        @Override
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap") || name.equals("Essentials")) {
                if(dynmap.isEnabled() && essentials.isEnabled())
                    activate();
            }
        }
    }
    
    public void onEnable() {
        info("initializing");
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if(dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }
        api = (DynmapAPI)dynmap; /* Get API */
        /* Get Essentials */
        Plugin p = pm.getPlugin("Essentials");
        if(p == null) {
            severe("Cannot find Essentials!");
            return;
        }
        essentials = (Essentials)p;
        /* If both enabled, activate */
        if(dynmap.isEnabled() && essentials.isEnabled())
            activate();
        else
            getServer().getPluginManager().registerEvent(Type.PLUGIN_ENABLE, new OurServerListener(), Priority.Monitor, this);        
    }

    private void activate() {
        /* Now, get markers API */
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            severe("Error loading Dynmap marker API!");
            return;
        }
        /* Now, get essentials Warp API */
        warps = essentials.getWarps();
        /* Get essentials user data API */
        users = essentials.getUserMap();
        
        /* If not found, signal disabled */
        if(warps == null)
            info("Essentials Warps not found - support disabled");
        /* If not found, signal disabled */
        if(users == null)
            info("Essentials Homes not found - support disabled");
            
        /* Load configuration */
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
        
        /* Check which is enabled */
        if(cfg.getBoolean("layer.homes.enable", true) == false)
            users = null;
        if(cfg.getBoolean("layer.warps.enable", true) == false)
            warps = null;
        
        /* Now, add marker set for homes */
        if(users != null)
            homelayer = new HomesLayer(cfg);
        /* Now, add marker set for warps */
        if(warps != null)
            warplayer = new WarpsLayer(cfg);
        
        /* Set up update job - based on periond */
        double per = cfg.getDouble("update.period", 5.0);
        if(per < 2.0) per = 2.0;
        updperiod = (long)(per*20.0);
        stop = false;
        getServer().getScheduler().scheduleSyncDelayedTask(this, new MarkerUpdate(), 5*20);
        
        info("version " + this.getDescription().getVersion() + " is activated");
    }

    public void onDisable() {
        if(homelayer != null) {
            homelayer.cleanup();
            homelayer = null;
        }
        if(warplayer != null) {
            warplayer.cleanup();
            warplayer = null;
        }
        stop = true;
    }

}
