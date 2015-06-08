/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.security.permission;

import static org.wildfly.security.manager._private.SecurityMessages.permission;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;

/**
 * A helper class for defining permissions which use a finite list of actions.  Define custom permissions using
 * an {@code enum} of actions, where the string representation (via {@code toString()}) of each enum is one possible
 * action name.  Typically the {@code enum} should be non-public, and the constant names should be lowercase.  If
 * an action name contains a character which is not a valid Java identifier, then the {@code toString()} method of
 * such constants should be overridden to report the correct string.  The actions may be stored on the permission as
 * an {@code EnumSet}, an {@code int}, or a {@code long}.  The field should be marked {@code transient}, and
 * the actions represented by a (possibly synthetic) field of type {@code String} which uses the canonical representation
 * of the actions.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class PermissionActions {

    private PermissionActions() {
    }

    static final class TrieNode<E> {
        private static final char[] C_EMPTY = new char[0];
        private static final TrieNode[] T_EMPTY = new TrieNode[0];

        private E result;
        private char[] matches = C_EMPTY;
        @SuppressWarnings("unchecked")
        private TrieNode<E>[] children = T_EMPTY;

        void put(String s, int idx, E value) {
            if (idx == s.length()) {
                result = value;
                return;
            }
            char c = s.charAt(idx);
            final int i = Arrays.binarySearch(matches, c);
            if (i < 0) {
                // copy and add
                final int oldLength = matches.length;
                final char[] newMatches = Arrays.copyOf(matches, oldLength + 1);
                final TrieNode<E>[] newChildren = Arrays.copyOf(children, oldLength + 1);
                // i is the negated insertion index
                final int insertIndex = -i - 1;
                System.arraycopy(newMatches, insertIndex, newMatches, insertIndex + 1, oldLength - insertIndex);
                System.arraycopy(newChildren, insertIndex, newChildren, insertIndex + 1, oldLength - insertIndex);
                newMatches[insertIndex] = c;
                final TrieNode<E> newNode = new TrieNode<>();
                newChildren[insertIndex] = newNode;
                matches = newMatches;
                children = newChildren;
                newNode.put(s, idx + 1, value);
            } else {
                children[i].put(s, idx + 1, value);
            }
        }

        E get(String s, int idx, int end) {
            if (idx == end) {
                return result;
            }
            final char c = s.charAt(idx);
            final int i = Arrays.binarySearch(matches, c);
            if (i < 0) {
                return null;
            }
            return children[i].get(s, idx + 1, end);
        }
    }

    static final class Info<E> {
        final TrieNode<E> root;
        final E[] constants;

        Info(final TrieNode<E> root, final E[] constants) {
            this.root = root;
            this.constants = constants;
        }
    }

    private static final ClassValue<Info<?>> storedInfo = new ClassValue<Info<?>>() {
        protected Info<?> computeValue(final Class<?> type) {
            return computeReal(type);
        }

        private <E> Info<E> computeReal(final Class<E> type) {
            final TrieNode<E> root = new TrieNode<>();
            final E[] enumConstants = type.getEnumConstants();
            for (E e : enumConstants) {
                root.put(e.toString(), 0, e);
            }
            return new Info<>(root, type.getEnumConstants());
        }
    };

    interface MatchAction<E extends Enum<E>> {
        void matched(E item);

        void matchedAll(Class<E> type);
    }

    static class SetMatchAction<E extends Enum<E>> implements MatchAction<E> {
        private EnumSet<E> set;

        SetMatchAction(final EnumSet<E> set) {
            this.set = set;
        }

        public void matched(final E item) {
            set.add(item);
        }

        public void matchedAll(final Class<E> type) {
            set = EnumSet.allOf(type);
        }

        public EnumSet<E> getSet() {
            return set;
        }
    }

    static class IntMatchAction<E extends Enum<E>> implements MatchAction<E> {
        private int result;

        IntMatchAction() {
        }

        public void matched(final E item) {
            result |= 1 << item.ordinal();
        }

        public void matchedAll(final Class<E> type) {
            result |= (1 << storedInfo.get(type).constants.length) - 1;
        }

        public int getResult() {
            return result;
        }
    }

    static class LongMatchAction<E extends Enum<E>> implements MatchAction<E> {
        private long result;

        LongMatchAction() {
        }

        public void matched(final E item) {
            result |= 1L << item.ordinal();
        }

        public void matchedAll(final Class<E> type) {
            result |= (1L << storedInfo.get(type).constants.length) - 1;
        }

        public long getResult() {
            return result;
        }
    }

    /**
     * Parse an action string using the given action type to an {@code EnumSet}.
     *
     * @param actionType the action {@code enum} type class
     * @param actionString the string to parse
     * @param <E> the action {@code enum} type
     *
     * @return the set of actions from the string
     *
     * @throws IllegalArgumentException if the string contained an invalid action
     */
    public static <E extends Enum<E>> EnumSet<E> parseActionStringToSet(Class<E> actionType, String actionString) throws IllegalArgumentException {
        if (actionString == null) {
            throw new IllegalArgumentException("actionString is null");
        }
        if (actionType == null) {
            throw new IllegalArgumentException("actionType is null");
        }
        final SetMatchAction<E> matchAction = new SetMatchAction<>(EnumSet.noneOf(actionType));
        doParse(actionType, actionString, matchAction);
        return matchAction.getSet();
    }

    /**
     * Parse an action string using the given action type to an {@code int}.  The given {@code enum} type must have
     * 32 or fewer constant values.
     *
     * @param actionType the action {@code enum} type class
     * @param actionString the string to parse
     * @param <E> the action {@code enum} type
     *
     * @return the set of actions from the string
     *
     * @throws IllegalArgumentException if the string contained an invalid action
     */
    public static <E extends Enum<E>> int parseActionStringToInt(Class<E> actionType, String actionString) throws IllegalArgumentException {
        if (actionString == null) {
            throw new IllegalArgumentException("actionString is null");
        }
        if (actionType == null) {
            throw new IllegalArgumentException("actionType is null");
        }
        final IntMatchAction<E> matchAction = new IntMatchAction<>();
        doParse(actionType, actionString, matchAction);
        return matchAction.getResult();
    }

    /**
     * Parse an action string using the given action type to a {@code long}.  The given {@code enum} type must have
     * 64 or fewer constant values.
     *
     * @param actionType the action {@code enum} type class
     * @param actionString the string to parse
     * @param <E> the action {@code enum} type
     *
     * @return the set of actions from the string
     *
     * @throws IllegalArgumentException if the string contained an invalid action
     */
    public static <E extends Enum<E>> long parseActionStringToLong(Class<E> actionType, String actionString) throws IllegalArgumentException {
        if (actionString == null) {
            throw new IllegalArgumentException("actionString is null");
        }
        if (actionType == null) {
            throw new IllegalArgumentException("actionType is null");
        }
        final LongMatchAction<E> matchAction = new LongMatchAction<>();
        doParse(actionType, actionString, matchAction);
        return matchAction.getResult();
    }

    private static <E extends Enum<E>> void doParse(final Class<E> actionType, final String actionString, final MatchAction<E> matchAction) {
        @SuppressWarnings("unchecked")
        final Info<E> info = (Info<E>) storedInfo.get(actionType);
        final TrieNode<E> rootNode = info.root;
        // begin parse
        char c;
        final int length = actionString.length();
        int i = 0;
        L0: for (;;) {
            if (i == length) {
                // OK
                break L0;
            }
            c = actionString.charAt(i);
            if (Character.isWhitespace(c)) {
                i ++;
                continue L0;
            }
            if (c == ',') {
                // hmm, empty segment; ignore it
                i ++;
                continue L0;
            }
            if (c == '*') {
                // potential star
                matchAction.matchedAll(actionType);
                for (;;) {
                    i ++;
                    if (i == length) {
                        // done
                        break L0;
                    }
                    c = actionString.charAt(i);
                    if (c == ',') {
                        // pointless, but go on
                        i ++;
                        continue L0;
                    }
                    if (! Character.isWhitespace(c)) {
                        throw permission.unexpectedActionCharacter(c, i, actionString);
                    }
                }
                // not reachable
            }
            // else it's a potentially valid character
            int start = i;
            for (;;) {
                i++;
                c = i < length ? actionString.charAt(i) : 0;
                if (i == length || Character.isWhitespace(c) || c == ',') {
                    // action string ends here
                    final E action = rootNode.get(actionString, start, i);
                    if (action == null) {
                        throw permission.invalidAction(actionString.substring(start, i), start, actionString);
                    }
                    matchAction.matched(action);
                    if (i == length) {
                        // done
                        break L0;
                    }
                    while (Character.isWhitespace(c)) {
                        i++;
                        if (i == length) {
                            // done
                            break L0;
                        }
                        c = actionString.charAt(i);
                    }
                    if (c != ',') {
                        throw permission.unexpectedActionCharacter(c, i, actionString);
                    }
                    i ++;
                    continue L0;
                }
            }
            // not reachable
        }
    }

    /**
     * Get the canonical action string representation for the given action set.
     *
     * @param set the action set
     * @param <E> the action type
     * @return the canonical representation
     */
    public static <E extends Enum<E>> String getCanonicalActionString(EnumSet<E> set) {
        if (set == null || set.isEmpty()) return "";
        final StringBuilder b = new StringBuilder();
        getCanonicalActionString(set, b);
        return b.toString();
    }

    /**
     * Get the canonical action string representation for the given action set, appending it to the given string builder.
     *
     * @param set the action set
     * @param b the string builder
     * @param <E> the action type
     */
    public static <E extends Enum<E>> void getCanonicalActionString(EnumSet<E> set, StringBuilder b) {
        if (set == null || set.isEmpty()) return;
        final Iterator<E> iterator = set.iterator();
        if (iterator.hasNext()) {
            E e = iterator.next();
            b.append(e.toString());
            while (iterator.hasNext()) {
                e = iterator.next();
                b.append(',');
                b.append(e.toString());
            }
        }
    }

    /**
     * Get the canonical action string representation for the given action set.
     *
     * @param type the action {@code enum} type class
     * @param set the action set
     * @param <E> the action type
     * @return the canonical representation
     */
    public static <E extends Enum<E>> String getCanonicalActionString(Class<E> type, int set) {
        if (set == 0) return "";
        final StringBuilder b = new StringBuilder();
        getCanonicalActionString(type, set, b);
        return b.toString();
    }

    /**
     * Get the canonical action string representation for the given action set, appending it to the given string builder.
     *
     * @param type the action {@code enum} type class
     * @param set the action set
     * @param b the string builder
     * @param <E> the action type
     */
    public static <E extends Enum<E>> void getCanonicalActionString(Class<E> type, int set, StringBuilder b) {
        if (set == 0) return;
        @SuppressWarnings("unchecked")
        final E[] constants = (E[]) storedInfo.get(type).constants;
        int bit = Integer.lowestOneBit(set);
        E e = constants[Integer.numberOfTrailingZeros(bit)];
        b.append(e.toString());
        set &= ~bit;
        while (set != 0) {
            bit = Integer.lowestOneBit(set);
            e = constants[Integer.numberOfTrailingZeros(bit)];
            b.append(',').append(e.toString());
            set &= ~bit;
        }
    }

    /**
     * Get the canonical action string representation for the given action set.
     *
     * @param type the action {@code enum} type class
     * @param set the action set
     * @param <E> the action type
     * @return the canonical representation
     */
    public static <E extends Enum<E>> String getCanonicalActionString(Class<E> type, long set) {
        if (set == 0) return "";
        final StringBuilder b = new StringBuilder();
        getCanonicalActionString(type, set, b);
        return b.toString();
    }

    /**
     * Get the canonical action string representation for the given action set, appending it to the given string builder.
     *
     * @param type the action {@code enum} type class
     * @param set the action set
     * @param b the string builder
     * @param <E> the action type
     */
    public static <E extends Enum<E>> void getCanonicalActionString(Class<E> type, long set, StringBuilder b) {
        if (set == 0) return;
        @SuppressWarnings("unchecked")
        final E[] constants = (E[]) storedInfo.get(type).constants;
        long bit = Long.lowestOneBit(set);
        E e = constants[Long.numberOfTrailingZeros(bit)];
        b.append(e.toString());
        set &= ~bit;
        while (set != 0) {
            bit = Long.lowestOneBit(set);
            e = constants[Long.numberOfTrailingZeros(bit)];
            b.append(',').append(e.toString());
            set &= ~bit;
        }
    }
}