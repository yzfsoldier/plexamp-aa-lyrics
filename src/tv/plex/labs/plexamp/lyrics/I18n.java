/*
 * Plexamp AA Lyrics – synced lyrics for Plexamp on Android Auto
 * Copyright (C) 2026 yzfsoldier
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package tv.plex.labs.plexamp.lyrics;

import java.util.Locale;

/** Minimal localization: German when the phone is set to German, English otherwise. */
final class I18n {

    private I18n() {}

    static boolean german() {
        return "de".equals(Locale.getDefault().getLanguage());
    }

    /** Picks the German or English variant depending on the current phone language. */
    static String t(String de, String en) {
        return german() ? de : en;
    }
}
