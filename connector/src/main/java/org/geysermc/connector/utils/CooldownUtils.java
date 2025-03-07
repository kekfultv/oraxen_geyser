/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.utils;

import com.nukkitx.protocol.bedrock.packet.SetTitlePacket;
import lombok.Getter;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.connector.network.session.cache.PreferencesCache;

import java.util.concurrent.TimeUnit;

/**
 * Manages the sending of a cooldown indicator to the Bedrock player as there is no cooldown indicator in Bedrock.
 * Much of the work here is from the wonderful folks from ViaRewind: https://github.com/ViaVersion/ViaRewind
 */
public class CooldownUtils {
    private static CooldownType DEFAULT_SHOW_COOLDOWN;

    public static void setDefaultShowCooldown(String showCooldown) {
        DEFAULT_SHOW_COOLDOWN = CooldownType.getByName(showCooldown);
    }

    public static CooldownType getDefaultShowCooldown() {
        return DEFAULT_SHOW_COOLDOWN;
    }

    /**
     * Starts sending the fake cooldown to the Bedrock client. If the cooldown is not disabled, the sent type is the cooldownPreference in {@link PreferencesCache}
     * @param session GeyserSession
     */
    public static void sendCooldown(GeyserSession session) {
        if (DEFAULT_SHOW_COOLDOWN == CooldownType.DISABLED) return;
        CooldownType sessionPreference = session.getPreferencesCache().getCooldownPreference();
        if (sessionPreference == CooldownType.DISABLED) return;

        if (session.getAttackSpeed() == 0.0 || session.getAttackSpeed() > 20) return; // 0.0 usually happens on login and causes issues with visuals; anything above 20 means a plugin like OldCombatMechanics is being used
        // Needs to be sent or no subtitle packet is recognized by the client
        SetTitlePacket titlePacket = new SetTitlePacket();
        titlePacket.setType(SetTitlePacket.Type.TITLE);
        titlePacket.setText(" ");
        titlePacket.setXuid("");
        titlePacket.setPlatformOnlineId("");
        session.sendUpstreamPacket(titlePacket);
        session.setLastHitTime(System.currentTimeMillis());
        long lastHitTime = session.getLastHitTime(); // Used later to prevent multiple scheduled cooldown threads
        computeCooldown(session, sessionPreference, lastHitTime);
    }

    /**
     * Keeps updating the cooldown until the bar is complete.
     * @param session GeyserSession
     * @param sessionPreference The type of cooldown the client prefers
     * @param lastHitTime The time of the last hit. Used to gauge how long the cooldown is taking.
     */
    private static void computeCooldown(GeyserSession session, CooldownType sessionPreference, long lastHitTime) {
        if (session.isClosed()) return; // Don't run scheduled tasks if the client left
        if (lastHitTime != session.getLastHitTime()) return; // Means another cooldown has started so there's no need to continue this one
        SetTitlePacket titlePacket = new SetTitlePacket();
        if (sessionPreference == CooldownType.ACTIONBAR) {
            titlePacket.setType(SetTitlePacket.Type.ACTIONBAR);
        } else {
            titlePacket.setType(SetTitlePacket.Type.SUBTITLE);
        }
        titlePacket.setText(getTitle(session));
        titlePacket.setFadeInTime(0);
        titlePacket.setFadeOutTime(5);
        titlePacket.setStayTime(2);
        titlePacket.setXuid("");
        titlePacket.setPlatformOnlineId("");
        session.sendUpstreamPacket(titlePacket);
        if (hasCooldown(session)) {
            session.getConnector().getGeneralThreadPool().schedule(() -> computeCooldown(session, sessionPreference, lastHitTime), 50, TimeUnit.MILLISECONDS); // Updated per tick. 1000 divided by 20 ticks equals 50
        } else {
            SetTitlePacket removeTitlePacket = new SetTitlePacket();
            if (sessionPreference == CooldownType.ACTIONBAR) {
                removeTitlePacket.setType(SetTitlePacket.Type.ACTIONBAR);
            } else {
                removeTitlePacket.setType(SetTitlePacket.Type.SUBTITLE);
            }
            removeTitlePacket.setText(" ");
            removeTitlePacket.setXuid("");
            removeTitlePacket.setPlatformOnlineId("");
            session.sendUpstreamPacket(removeTitlePacket);
        }
    }

    private static boolean hasCooldown(GeyserSession session) {
        long time = System.currentTimeMillis() - session.getLastHitTime();
        double cooldown = restrain(((double) time) * session.getAttackSpeed() / 1000d, 1.5);
        return cooldown < 1.1;
    }


    private static double restrain(double x, double max) {
        if (x < 0d)
            return 0d;
        return Math.min(x, max);
    }

    private static String getTitle(GeyserSession session) {
        long time = System.currentTimeMillis() - session.getLastHitTime();
        double cooldown = restrain(((double) time) * session.getAttackSpeed() / 1000d, 1);

        int darkGrey = (int) Math.floor(10d * cooldown);
        int grey = 10 - darkGrey;
        StringBuilder builder = new StringBuilder("§8");
        while (darkGrey > 0) {
            builder.append("˙");
            darkGrey--;
        }
        builder.append("§7");
        while (grey > 0) {
            builder.append("˙");
            grey--;
        }
        return builder.toString();
    }

    @Getter
    public enum CooldownType {
        TITLE,
        ACTIONBAR,
        DISABLED;

        public static final CooldownType[] VALUES = values();

        /**
         * Convert the CooldownType string (from config) to the enum, DISABLED on fail
         *
         * @param name CooldownType string
         *
         * @return The converted CooldownType
         */
        public static CooldownType getByName(String name) {
            if (name.equalsIgnoreCase("true")) { // Backwards config compatibility
                return CooldownType.TITLE;
            }

            for (CooldownType type : VALUES) {
                if (type.name().equalsIgnoreCase(name)) {
                    return type;
                }
            }
            return DISABLED;
        }
    }
}
