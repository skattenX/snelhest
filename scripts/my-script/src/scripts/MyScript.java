package scripts;

import org.tribot.script.sdk.Log;
import org.tribot.script.sdk.script.TribotScript;
import org.tribot.script.sdk.script.TribotScriptManifest;
import org.tribot.script.sdk.util.Resources;

@TribotScriptManifest(name = "MyScript", author = "Me", category = "Template", description = "My example script")
public class MyScript implements TribotScript {

	@Override
	public void execute(final String args) {
		// Example: Call our shared library class
		SampleHelper.getHello();
		String resource = Resources.getString("scripts/my-resource.txt");
		Log.info("Loaded " + resource);
	}

}
package scripts;

import org.tribot.api.General;
import org.tribot.api.input.Mouse;
import org.tribot.api.util.abc.ABCUtil;
import org.tribot.api2007.*;
import org.tribot.api2007.types.RSItem;
import org.tribot.api2007.types.RSObject;
import org.tribot.api2007.types.RSTile;
import org.tribot.script.Script;
import org.tribot.script.ScriptManifest;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;

/**
 * Skatten - Polished Woodcutting + Firemaking script
 * Features:
 * - Friendly GUI with presets + "Set Area From Current Location"
 * - Auto-equip Rune axe
 * - Options: Bank / Drop / Light logs (Tinderbox)
 * - Auto-walk to set area center
 * - Anti-ban micro behaviours
 * - Robust inventory & bank handling
 *
 * WARNING: Using automation may violate the game's rules. Use at your own risk.
 */

@ScriptManifest(authors = {"YourName"}, name = "Skatten", category = "Woodcutting", description = "Polished Woodcutting + Firemaking (Rune axe) with GUI & anti-ban")
public class Skatten extends Script {

    // --- GUI / settings ---
    private String treeType = "Tree";
    private boolean bankLogs = false;
    private boolean lightLogs = false;
    private boolean useAntiBan = true;
    private boolean guiDone = false;

    // Area center + radius (set via GUI)
    private RSTile areaCenter = null;
    private int areaRadius = 8;

    // Constants
    private static final String RUNE_AXE = "Rune axe";
    private static final String TINDERBOX = "Tinderbox";

    // Utilities
    private final Random rand = new Random();
    private final ABCUtil abc = new ABCUtil();

    // Small state
    private long lastAntiBanAt = 0L;
    private final int ANTIBAN_MIN_MS = 7_000; // at least every 7s do micro anti-ban check (randomized more)
    private final int ANTIBAN_MAX_MS = 30_000;

    // Built-in named area presets (user can pick, but it's best to "Set Area From Current Location")
    // These are labels only — coordinates intentionally left unset (encourage user to set area).
    private final Map<String, RSTile> PRESET_AREAS = new LinkedHashMap<String, RSTile>() {{
        put("Draynor Village - Willows (preset)", null);
        put("Seers' Village - Maple / Yew (preset)", null);
        put("Woodcutting Guild (preset)", null);
        put("Hosidius - Teak (preset)", null);
        put("Edgeville - Yew (preset)", null);
    }};

    @Override
    public void run() {
        SwingUtilities.invokeLater(this::openGUI);

        // wait for GUI
        while (!guiDone) {
            General.sleep(200);
        }

        println("=== Skatten starting ===");
        println("Tree: " + treeType + " | Bank: " + bankLogs + " | Light: " + lightLogs + " | AntiBan: " + useAntiBan);
        if (areaCenter != null) println("Area center: " + areaCenter + " radius: " + areaRadius);
        else println("No area set. Use 'Set Area From Current Location' for best results.");

        // Main loop
        while (!isStopped()) {
            // Keep run on sometimes
            tryToggleRun();

            // Anti-ban micro actions (periodic)
            maybePerformAntiBan();

            // If area set, ensure player is inside area; otherwise try to walk to it.
            if (areaCenter != null && !isPlayerInArea(areaCenter, areaRadius)) {
                println("Walking to area center...");
                walkToAreaCenter();
                // small sleep after walking
                General.sleep(randInt(600, 1400));
            }

            // Equip rune axe if not equipped
            equipRuneAxe();

            // Manage inventory: if full -> decide action
            if (Inventory.isFull()) {
                if (lightLogs && hasItem(TINDERBOX) && hasAnyLogsInInventory()) {
                    // prioritize lighting logs to train firemaking and free space
                    lightAllLogs();
                } else if (bankLogs) {
                    if (!bankAllLogs()) {
                        // bank failed (not near bank) -> fallback to drop to avoid being stuck
                        println("Bank failed - dropping logs as fallback.");
                        dropAllLogs();
                    }
                } else {
                    dropAllLogs();
                }
                // small sleep after inventory management
                General.sleep(randInt(400, 900));
                continue;
            }

            // Try to chop a nearby tree
            boolean chopped = chopNearbyTree();

            if (!chopped) {
                // nothing found — either wander a bit inside area or idle for short bit
                smallWanderOrIdle();
            }

            // short, randomized sleep to be human-like
            General.sleep(randInt(300, 900));
        }
    }

    // ---------------- GUI ----------------
    private void openGUI() {
        JFrame frame = new JFrame("Skatten - Setup");
        frame.setSize(460, 360);
        frame.setLayout(new GridBagLayout());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("🌲 Skatten - Woodcutting & Firemaking", SwingConstants.CENTER);
        title.setFont(new Font("Dialog", Font.BOLD, 16));
        c.gridx = 0; c.gridy = 0; c.gridwidth = 3; c.insets = new Insets(6,6,6,6);
        frame.add(title, c);

        // Tree type
        c.gridwidth = 1;
        c.gridx = 0; c.gridy = 1;
        frame.add(new JLabel("Tree type:"), c);
        String[] trees = {"Tree", "Oak", "Willow", "Maple", "Yew", "Teak", "Mahogany", "Magic"};
        JComboBox<String> treeDropdown = new JComboBox<>(trees);
        c.gridx = 1; c.gridy = 1; c.gridwidth = 2;
        frame.add(treeDropdown, c);

        // Area presets dropdown
        c.gridwidth = 1;
        c.gridx = 0; c.gridy = 2;
        frame.add(new JLabel("Area preset:"), c);
        JComboBox<String> presetDropdown = new JComboBox<>(PRESET_AREAS.keySet().toArray(new String[0]));
        c.gridx = 1; c.gridy = 2; c.gridwidth = 2;
        frame.add(presetDropdown, c);

        // Area label & Set button
        c.gridwidth = 1;
        c.gridx = 0; c.gridy = 3;
        JLabel areaLabel = new JLabel("Area: (not set)");
        frame.add(areaLabel, c);
        JButton setAreaBtn = new JButton("Set Area From Current Location");
        c.gridx = 1; c.gridy = 3; c.gridwidth = 2;
        frame.add(setAreaBtn, c);

        setAreaBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                RSTile p = Player.getPosition();
                if (p != null) {
                    areaCenter = p;
                    areaLabel.setText("Area set: x=" + p.getX() + " y=" + p.getY());
                    println("Area set to: " + p);
                } else {
                    areaLabel.setText("Area: could not read position");
                }
            }
        });

        // Radius spinner
        c.gridwidth = 1;
        c.gridx = 0; c.gridy = 4;
        frame.add(new JLabel("Radius (tiles):"), c);
        SpinnerNumberModel radiusModel = new SpinnerNumberModel(areaRadius, 3, 20, 1);
        JSpinner radiusSpinner = new JSpinner(radiusModel);
        c.gridx = 1; c.gridy = 4; c.gridwidth = 2;
        frame.add(radiusSpinner, c);

        // Options: bank / light / anti-ban
        c.gridwidth = 1;
        c.gridx = 0; c.gridy = 5;
        JCheckBox bankBox = new JCheckBox("Bank logs (keep Rune axe)");
        frame.add(bankBox, c);
        c.gridx = 1; c.gridy = 5;
        JCheckBox lightBox = new JCheckBox("Light logs with Tinderbox (Firemaking)");
        frame.add(lightBox, c);
        c.gridx = 2; c.gridy = 5;
        JCheckBox antiBox = new JCheckBox("Enable anti-ban behaviours", true);
        frame.add(antiBox, c);

        // Start button
        JButton start = new JButton("Start Skatten");
        start.setPreferredSize(new Dimension(200, 36));
        c.gridx = 0; c.gridy = 6; c.gridwidth = 3; c.insets = new Insets(12,6,6,6);
        frame.add(start, c);

        // Helpful note label
        c.gridx = 0; c.gridy = 7; c.gridwidth = 3;
        JLabel note = new JLabel("<html><center>Tip: Stand where you want the bot to chop and click 'Set Area From Current Location' — this creates an exact area.</center></html>", SwingConstants.CENTER);
        frame.add(note, c);

        // Preset selection action (only labels—user must set area for reliable coords)
        presetDropdown.addActionListener(e -> {
            String key = (String) presetDropdown.getSelectedItem();
            if (key != null && PRESET_AREAS.get(key) != null) {
                areaCenter = PRESET_AREAS.get(key);
                areaLabel.setText("Area preset applied (coords): " + areaCenter.getX() + "," + areaCenter.getY());
            } else if (key != null) {
                // Preset has no coords; inform user to set location manually
                areaLabel.setText("Preset selected: " + key + " (use 'Set Area' to capture coords)");
            }
        });

        start.addActionListener(e -> {
            treeType = (String) treeDropdown.getSelectedItem();
            bankLogs = bankBox.isSelected();
            lightLogs = lightBox.isSelected();
            useAntiBan = antiBox.isSelected();
            areaRadius = (Integer) radiusSpinner.getValue();
            guiDone = true;
            frame.dispose();
        });

        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ---------------- Core logic helpers ----------------

    private boolean chopNearbyTree() {
        RSObject[] trees = Objects.findNearest(12, treeType);
        if (trees == null || trees.length == 0) return false;

        RSObject tree = trees[0];
        if (tree == null) return false;

        // If not busy, click chop
        if (!Player.isMoving() && !Player.getRSPlayer().isInCombat()) {
            if (tree.click("Chop down")) {
                println("Clicked to chop " + treeType);
                sleepUntilNotAnimatingOrTimeout(300);
                // small chance to do anti-ban after a chop
                if (useAntiBan && rand.nextInt(5) == 0) maybePerformAntiBan();
                return true;
            }
        }
        return false;
    }

    private void equipRuneAxe() {
        try {
            if (Equipment.isEquipped(RUNE_AXE)) return;
            RSItem[] axe = Inventory.find(RUNE_AXE);
            if (axe != null && axe.length > 0) {
                if (axe[0].click("Wield")) {
                    General.sleep(randInt(700, 1400));
                    println("Equipped Rune axe.");
                }
            }
        } catch (Exception e) {
            println("Exception equipping rune axe: " + e.getMessage());
        }
    }

    private void dropAllLogs() {
        String[] logs = {"Logs", "Oak logs", "Willow logs", "Maple logs", "Yew logs", "Teak logs", "Mahogany logs", "Magic logs"};
        for (String l : logs) {
            RSItem[] items = Inventory.find(l);
            for (RSItem it : items) {
                if (it != null) {
                    it.click("Drop");
                    General.sleep(randInt(90, 250));
                }
            }
        }
        println("Dropped logs.");
    }

    private boolean bankAllLogs() {
        // Returns true if banking succeeded
        if (!Banking.openBank()) {
            println("Bank open failed or no bank nearby.");
            return false;
        }
        // deposit all except rune axe
        Banking.depositAllExcept(RUNE_AXE);
        General.sleep(randInt(500, 1000));
        Banking.close();
        println("Banked logs (kept Rune axe).");
        return true;
    }

    private void lightAllLogs() {
        RSItem[] tinder = Inventory.find(TINDERBOX);
        if (tinder == null || tinder.length == 0) {
            println("No Tinderbox in inventory; cannot light logs.");
            return;
        }

        String[] logs = {"Logs", "Oak logs", "Willow logs", "Maple logs", "Yew logs", "Teak logs", "Mahogany logs", "Magic logs"};
        for (String ln : logs) {
            RSItem[] items = Inventory.find(ln);
            for (RSItem log : items) {
                if (log == null) continue;
                // Use tinderbox then log
                if (!Inventory.isItemSelected()) {
                    if (tinder[0].click("Use")) {
                        General.sleep(randInt(180, 320));
                    } else {
                        General.sleep(randInt(100, 220));
                    }
                }
                if (Inventory.isItemSelected()) {
                    if (log.click("Use")) {
                        println("Lighting " + ln);
                        // Wait while performing firemaking animation or small timeout
                        sleepUntilNotAnimatingOrTimeout(160);
                        General.sleep(randInt(250, 700));
                    }
                }
            }
        }
    }

    // ---------------- Anti-ban behaviors ----------------
    private void maybePerformAntiBan() {
        if (!useAntiBan) return;
        long now = System.currentTimeMillis();
        if (now - lastAntiBanAt < randInt(ANTIBAN_MIN_MS, ANTIBAN_MAX_MS)) return;
        lastAntiBanAt = now;

        int act = rand.nextInt(6);
        switch (act) {
            case 0:
                // camera bob & rotate
                int rot = randInt(0, 360);
                int ang = randInt(25, 90);
                try { Camera.setCameraRotation(rot); Camera.setCameraAngle(ang); } catch (Exception ignored) {}
                General.sleep(randInt(300, 900));
                break;
            case 1:
                // small mouse move
                try { Mouse.move(randInt(100, 700), randInt(100, 400)); } catch (Exception ignored) {}
                General.sleep(randInt(200, 700));
                break;
            case 2:
                // open skills or inventory and look
                if (rand.nextBoolean()) Tabs.open(Tab.SKILLS);
                else Tabs.open(Tab.INVENTORY);
                General.sleep(randInt(350, 900));
                Tabs.open(Tab.INVENTORY);
                break;
            case 3:
                // short idle / yawn
                General.sleep(randInt(700, 2200));
                break;
            case 4:
                // micro keyboard like action (abc util)
                try { abc.sleepReactionTime(); } catch (Exception ignored) { General.sleep(randInt(300, 800)); }
                break;
            case 5:
                // tiny viewport pan
                try { Camera.setCameraRotation(randInt(0, 360)); } catch (Exception ignored) {}
                General.sleep(randInt(200, 600));
                break;
        }
    }

    // ---------------- Misc helpers ----------------

    private boolean hasAnyLogsInInventory() {
        String[] logs = {"Logs", "Oak logs", "Willow logs", "Maple logs", "Yew logs", "Teak logs", "Mahogany logs", "Magic logs"};
        for (String l : logs) {
            if (Inventory.find(l).length > 0) return true;
        }
        return false;
    }

    private boolean hasItem(String name) {
        return Inventory.find(name).length > 0 || Equipment.find(name).length > 0;
    }

    private void smallWanderOrIdle() {
        if (areaCenter != null && rand.nextInt(3) == 0) {
            // walk to a nearby tile inside radius to find new trees
            int dx = randInt(-areaRadius, areaRadius);
            int dy = randInt(-areaRadius, areaRadius);
            RSTile target = new RSTile(areaCenter.getX() + dx, areaCenter.getY() + dy, areaCenter.getPlane());
            Walking.walkTo(target);
            General.sleep(randInt(600, 1200));
        } else {
            // idle & anti-ban
            maybePerformAntiBan();
            General.sleep(randInt(900, 1700));
        }
    }

    private void walkToAreaCenter() {
        if (areaCenter == null) return;
        Walking.walkTo(areaCenter);
        General.sleep(randInt(600, 1400));
    }

    private boolean isPlayerInArea(RSTile center, int radius) {
        if (center == null) return true;
        RSTile p = Player.getPosition();
        if (p == null) return false;
        return Math.abs(p.getX() - center.getX()) <= radius && Math.abs(p.getY() - center.getY()) <= radius;
    }

    private void sleepUntilNotAnimatingOrTimeout(int cycles) {
        int timeout = 0;
        while (Player.getAnimation() != -1 && timeout < cycles) {
            General.sleep(100);
            timeout++;
        }
    }

    private void tryToggleRun() {
        try {
            if (Game.getRunEnergy() > randInt(20, 60) && !Game.isRunOn()) {
                Options.setRunOn(true);
            }
        } catch (Exception ignored) {}
    }

    private int randInt(int min, int max) {
        return rand.nextInt((max - min) + 1) + min;
    }

    // small wrapper to check if script should stop
    private boolean isStopped() {
        return this.getState() == Script.STATE_STOPPED || this.getState() == Script.STATE_PAUSED;
    }
}
