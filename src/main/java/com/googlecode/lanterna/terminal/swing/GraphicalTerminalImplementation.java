/*
 * This file is part of lanterna (http://code.google.com/p/lanterna/).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2010-2015 Martin
 */
package com.googlecode.lanterna.terminal.swing;

import com.googlecode.lanterna.*;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.IOSafeTerminal;
import com.googlecode.lanterna.terminal.ResizeListener;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This is the class that does the heavy lifting for both {@link AWTTerminal} and {@link SwingTerminal}. It maintains
 * most of the external terminal state and also the main back buffer that is copied to the components area on draw
 * operations.
 *
 * @author martin
 */
@SuppressWarnings("serial")
abstract class GraphicalTerminalImplementation implements IOSafeTerminal {
    private final TerminalEmulatorDeviceConfiguration deviceConfiguration;
    private final TerminalEmulatorColorConfiguration colorConfiguration;
    private final VirtualTerminal virtualTerminal;
    private final BlockingQueue<KeyStroke> keyQueue;
    private final List<ResizeListener> resizeListeners;
    private final TerminalScrollController scrollController;
    private final DirtyCellsLookupTable dirtyCellsLookupTable;

    private final String enquiryString;
    private final EnumSet<SGR> activeSGRs;
    private TextColor foregroundColor;
    private TextColor backgroundColor;

    private boolean cursorIsVisible;
    private Timer blinkTimer;
    private boolean hasBlinkingText;
    private boolean blinkOn;

    private TerminalPosition lastDrawnCursorPosition;
    private int lastBufferUpdateScrollPosition;
    private int lastComponentWidth;
    private int lastComponentHeight;

    // We use two different data structures to optimize drawing
    //  * A map (as a two-dimensional array) of all characters currently visible inside this component
    //  * A backbuffer with the graphics content
    //
    // The buffer is the most important one as it allows us to re-use what was drawn earlier. It is not reset on every
    // drawing operation but updates just in those places where the map tells us the character has changed. Note that
    // when the component is resized, we always update the whole buffer.
    //
    // DON'T RELY ON THESE FOR SIZE! We make it a big bigger than necessary to make resizing smoother. Use the AWT/Swing
    // methods to get the correct dimensions or use {@link #getTerminalSize} to get the size in terminal space.
    private BufferedImage backbuffer;

    // Used as a middle-ground when copying large segments when scrolling
    private BufferedImage copybuffer;

    /**
     * Creates a new GraphicalTerminalImplementation component using custom settings and a custom scroll controller. The
     * scrolling controller will be notified when the terminal's history size grows and will be called when this class
     * needs to figure out the current scrolling position.
     * @param initialTerminalSize Initial size of the terminal, which will be used when calculating the preferred size
     *                            of the component. If null, it will default to 80x25. If the AWT layout manager forces
     *                            the component to a different size, the value of this parameter won't have any meaning
     * @param deviceConfiguration Device configuration to use for this SwingTerminal
     * @param colorConfiguration Color configuration to use for this SwingTerminal
     * @param scrollController Controller to use for scrolling, the object passed in will be notified whenever the
     *                         scrollable area has changed
     */
    GraphicalTerminalImplementation(
            TerminalSize initialTerminalSize,
            TerminalEmulatorDeviceConfiguration deviceConfiguration,
            TerminalEmulatorColorConfiguration colorConfiguration,
            TerminalScrollController scrollController) {

        //This is kind of meaningless since we don't know how large the
        //component is at this point, but we should set it to something
        if(initialTerminalSize == null) {
            initialTerminalSize = new TerminalSize(80, 24);
        }
        this.virtualTerminal = new VirtualTerminal(
                //deviceConfiguration.getLineBufferScrollbackSize(),
                initialTerminalSize//,
                //scrollController);
        );
        this.keyQueue = new LinkedBlockingQueue<KeyStroke>();
        this.resizeListeners = new CopyOnWriteArrayList<ResizeListener>();
        this.deviceConfiguration = deviceConfiguration;
        this.colorConfiguration = colorConfiguration;
        this.scrollController = scrollController;
        this.dirtyCellsLookupTable = new DirtyCellsLookupTable();

        this.activeSGRs = EnumSet.noneOf(SGR.class);
        this.foregroundColor = TextColor.ANSI.DEFAULT;
        this.backgroundColor = TextColor.ANSI.DEFAULT;
        this.cursorIsVisible = true;        //Always start with an activate and visible cursor
        this.enquiryString = "TerminalEmulator";
        this.lastDrawnCursorPosition = null;
        this.lastBufferUpdateScrollPosition = 0;
        this.lastComponentHeight = 0;
        this.lastComponentWidth = 0;
        this.backbuffer = null;  // We don't know the dimensions yet
        this.copybuffer = null;
        this.blinkTimer = null;
        this.hasBlinkingText = false;   // Assume initial content doesn't have any blinking text
        this.blinkOn = true;

        //Set the initial scrollable size
        //scrollObserver.newScrollableLength(fontConfiguration.getFontHeight() * terminalSize.getRows());
    }

    ///////////
    // First abstract methods that are implemented in AWTTerminalImplementation and SwingTerminalImplementation
    ///////////

    /**
     * Used to find out the font height, in pixels
     * @return Terminal font height in pixels
     */
    abstract int getFontHeight();

    /**
     * Used to find out the font width, in pixels
     * @return Terminal font width in pixels
     */
    abstract int getFontWidth();

    /**
     * Used when requiring the total height of the terminal component, in pixels
     * @return Height of the terminal component, in pixels
     */
    abstract int getHeight();

    /**
     * Used when requiring the total width of the terminal component, in pixels
     * @return Width of the terminal component, in pixels
     */
    abstract int getWidth();

    /**
     * Returning the AWT font to use for the specific character. This might not always be the same, in case a we are
     * trying to draw an unusual character (probably CJK) which isn't contained in the standard terminal font.
     * @param character Character to get the font for
     * @return Font to be used for this character
     */
    abstract Font getFontForCharacter(TextCharacter character);

    /**
     * Returns {@code true} if anti-aliasing is enabled, {@code false} otherwise
     * @return {@code true} if anti-aliasing is enabled, {@code false} otherwise
     */
    abstract boolean isTextAntiAliased();

    /**
     * Called by the {@code GraphicalTerminalImplementation} when it would like the OS to schedule a repaint of the
     * window
     */
    abstract void repaint();

    /**
     * Start the timer that triggers blinking
     */
    synchronized void startBlinkTimer() {
        if(blinkTimer != null) {
            // Already on!
            return;
        }
        blinkTimer = new Timer("LanternaTerminalBlinkTimer", true);
        blinkTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                blinkOn = !blinkOn;
                if(hasBlinkingText) {
                    repaint();
                }
            }
        }, deviceConfiguration.getBlinkLengthInMilliSeconds(), deviceConfiguration.getBlinkLengthInMilliSeconds());
    }

    /**
     * Stops the timer the triggers blinking
     */
    synchronized void stopBlinkTimer() {
        if(blinkTimer == null) {
            // Already off!
            return;
        }
        blinkTimer.cancel();
        blinkTimer = null;
    }

    ///////////
    // Implement all the Swing-related methods
    ///////////
    /**
     * Calculates the preferred size of this terminal
     * @return Preferred size of this terminal
     */
    synchronized Dimension getPreferredSize() {
        return new Dimension(getFontWidth() * virtualTerminal.getViewportSize().getColumns(),
                getFontHeight() * virtualTerminal.getViewportSize().getRows());
    }

    /**
     * Updates the back buffer (if necessary) and draws it to the component's surface
     * @param componentGraphics Object to use when drawing to the component's surface
     */
    synchronized void paintComponent(Graphics componentGraphics) {
        int width = getWidth();
        int height = getHeight();

        this.scrollController.updateModel(
                virtualTerminal.getBufferLineCount() * getFontHeight(),
                height);

        // Detect resize
        if(width != lastComponentWidth || height != lastComponentHeight) {
            int columns = width / getFontWidth();
            int rows = height / getFontHeight();
            int scrollOffset = scrollController.getScrollingOffset();
            TerminalSize terminalSize = virtualTerminal.getViewportSize().withColumns(columns).withRows(rows);
            virtualTerminal.setViewportSize(terminalSize);

            // Back buffer needs to be updated since the component size has changed
            updateBackBuffer(scrollOffset);
            for(ResizeListener listener : resizeListeners) {
                listener.onResized(this, terminalSize);
            }
        }
        // Detect scrolling
        else if(lastBufferUpdateScrollPosition != scrollController.getScrollingOffset()) {
            // User has used the scrollbar, we need to update the back buffer to reflect this
            updateBackBuffer(scrollController.getScrollingOffset());
        }

        ensureGraphicBufferHasRightSize();
        Rectangle clipBounds = componentGraphics.getClipBounds();
        if(clipBounds == null) {
            clipBounds = new Rectangle(0, 0, getWidth(), getHeight());
        }
        componentGraphics.drawImage(
                backbuffer,
                // Destination coordinates
                clipBounds.x,
                clipBounds.y,
                clipBounds.width,
                clipBounds.height,
                // Source coordinates
                clipBounds.x,
                clipBounds.y,
                clipBounds.width,
                clipBounds.height,
                null);

        // Take care of the left-over area at the bottom and right of the component where no character can fit
        int leftoverHeight = getHeight() % getFontHeight();
        int leftoverWidth = getWidth() % getFontWidth();
        componentGraphics.setColor(Color.BLACK);
        if(leftoverWidth > 0) {
            componentGraphics.fillRect(getWidth() - leftoverWidth, 0, leftoverWidth, getHeight());
        }

        //0, 0, getWidth(), getHeight(), 0, 0, getWidth(), getHeight(), null);
        this.lastComponentWidth = width;
        this.lastComponentHeight = height;
        componentGraphics.dispose();
        notifyAll();
    }

    private synchronized void updateBackBuffer(final int scrollOffsetFromTopInPixels) {
        long startTime = System.currentTimeMillis();
        final int fontWidth = getFontWidth();
        final int fontHeight = getFontHeight();

        //Retrieve the position of the cursor, relative to the scrolling state
        final TerminalPosition cursorPosition = virtualTerminal.getCursorPosition();
        final TerminalSize viewportSize = virtualTerminal.getViewportSize();

        final int firstVisibleRowIndex = scrollOffsetFromTopInPixels / fontHeight;
        final int lastVisibleRowIndex = (scrollOffsetFromTopInPixels + getHeight()) / fontHeight;

        //Setup the graphics object
        ensureGraphicBufferHasRightSize();
        final Graphics2D backbufferGraphics = backbuffer.createGraphics();

        if(isTextAntiAliased()) {
            backbufferGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            backbufferGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        }

        final AtomicBoolean foundBlinkingCharacters = new AtomicBoolean(deviceConfiguration.isCursorBlinking());
        buildDirtyCellsLookupTable(firstVisibleRowIndex, lastVisibleRowIndex);

        // Detect scrolling
        if(lastBufferUpdateScrollPosition < scrollOffsetFromTopInPixels) {
            int gap = scrollOffsetFromTopInPixels - lastBufferUpdateScrollPosition;
            if(gap / fontHeight < viewportSize.getRows()) {
                Graphics2D graphics = copybuffer.createGraphics();
                graphics.setClip(0, 0, getWidth(), getHeight() - gap);
                graphics.drawImage(backbuffer, 0, -gap, null);
                graphics.dispose();
                backbufferGraphics.drawImage(copybuffer, 0, 0, getWidth(), getHeight(), 0, 0, getWidth(), getHeight(), null);
                if(!dirtyCellsLookupTable.isAllDirty()) {
                    //Mark bottom rows as dirty so they are repainted
                    int previousLastVisibleRowIndex = (lastBufferUpdateScrollPosition + getHeight()) / fontHeight;
                    for(int row = previousLastVisibleRowIndex; row <= lastVisibleRowIndex; row++) {
                        dirtyCellsLookupTable.setRowDirty(row);
                    }
                }
            }
            else {
                dirtyCellsLookupTable.setAllDirty();
            }
        }
        else if(lastBufferUpdateScrollPosition > scrollOffsetFromTopInPixels) {
            int gap = lastBufferUpdateScrollPosition - scrollOffsetFromTopInPixels;
            if(gap / fontHeight < viewportSize.getRows()) {
                Graphics2D graphics = copybuffer.createGraphics();
                graphics.setClip(0, 0, getWidth(), getHeight() - gap);
                graphics.drawImage(backbuffer, 0, 0, null);
                graphics.dispose();
                backbufferGraphics.drawImage(copybuffer, 0, gap, getWidth(), getHeight(), 0, 0, getWidth(), getHeight() - gap, null);
                if(!dirtyCellsLookupTable.isAllDirty()) {
                    //Mark top rows as dirty so they are repainted
                    int previousFirstVisibleRowIndex = lastBufferUpdateScrollPosition / fontHeight;
                    for(int row = firstVisibleRowIndex; row <= previousFirstVisibleRowIndex; row++) {
                        dirtyCellsLookupTable.setRowDirty(row);
                    }
                }
            }
            else {
                dirtyCellsLookupTable.setAllDirty();
            }
        }

        // Detect component resize
        if(lastComponentWidth < getWidth()) {
            if(!dirtyCellsLookupTable.isAllDirty()) {
                //Mark right columns as dirty so they are repainted
                int lastVisibleColumnIndex = getWidth() / fontWidth;
                int previousLastVisibleColumnIndex = lastComponentWidth / fontWidth;
                for(int column = previousLastVisibleColumnIndex; column <= lastVisibleColumnIndex; column++) {
                    dirtyCellsLookupTable.setColumnDirty(column);
                }
            }
        }
        if(lastComponentHeight < getHeight()) {
            if(!dirtyCellsLookupTable.isAllDirty()) {
                //Mark bottom rows as dirty so they are repainted
                int previousLastVisibleRowIndex = (scrollOffsetFromTopInPixels + lastComponentHeight) / fontHeight;
                for(int row = previousLastVisibleRowIndex; row <= lastVisibleRowIndex; row++) {
                    dirtyCellsLookupTable.setRowDirty(row);
                }
            }
        }

        virtualTerminal.forEachLine(firstVisibleRowIndex, lastVisibleRowIndex, new VirtualTerminal.BufferWalker() {
            @Override
            public void drawLine(int rowNumber, VirtualTerminal.BufferLine bufferLine) {
                for(int column = 0; column < viewportSize.getColumns(); column++) {
                    TextCharacter textCharacter = bufferLine.getCharacterAt(column);
                    boolean atCursorLocation = cursorPosition.equals(column, rowNumber);
                    //If next position is the cursor location and this is a CJK character (i.e. cursor is on the padding),
                    //consider this location the cursor position since otherwise the cursor will be skipped
                    if(!atCursorLocation &&
                            cursorPosition.getColumn() == column + 1 &&
                            cursorPosition.getRow() == rowNumber &&
                            TerminalTextUtils.isCharCJK(textCharacter.getCharacter())) {
                        atCursorLocation = true;
                    }
                    if(dirtyCellsLookupTable.isAllDirty() || dirtyCellsLookupTable.isDirty(rowNumber, column)) {
                        int characterWidth = fontWidth * (TerminalTextUtils.isCharCJK(textCharacter.getCharacter()) ? 2 : 1);
                        Color foregroundColor = deriveTrueForegroundColor(textCharacter, atCursorLocation);
                        Color backgroundColor = deriveTrueBackgroundColor(textCharacter, atCursorLocation);
                        boolean drawCursor = atCursorLocation &&
                                (!deviceConfiguration.isCursorBlinking() ||     //Always draw if the cursor isn't blinking
                                        (deviceConfiguration.isCursorBlinking() && blinkOn));    //If the cursor is blinking, only draw when blinkOn is true

                        drawCharacter(backbufferGraphics,
                                textCharacter,
                                column,
                                rowNumber,
                                foregroundColor,
                                backgroundColor,
                                fontWidth,
                                fontHeight,
                                characterWidth,
                                scrollOffsetFromTopInPixels,
                                drawCursor);
                    }

                    if(textCharacter.getModifiers().contains(SGR.BLINK)) {
                        foundBlinkingCharacters.set(true);
                    }
                    if(TerminalTextUtils.isCharCJK(textCharacter.getCharacter())) {
                        column++; //Skip the trailing space after a CJK character
                    }
                }
            }
        });

        backbufferGraphics.dispose();

        // Update the blink status according to if there were any blinking characters or not
        this.hasBlinkingText = foundBlinkingCharacters.get();
        this.lastDrawnCursorPosition = cursorPosition;
        this.lastBufferUpdateScrollPosition = scrollOffsetFromTopInPixels;

        //System.out.println("Updated backbuffer in " + (System.currentTimeMillis() - startTime) + " ms");
    }

    private void buildDirtyCellsLookupTable(int firstRowOffset, int lastRowOffset) {
        if(virtualTerminal.isWholeBufferDirtyThenReset()) {
            dirtyCellsLookupTable.setAllDirty();
            return;
        }

        TerminalSize viewportSize = virtualTerminal.getViewportSize();
        TerminalPosition cursorPosition = virtualTerminal.getCursorPosition();

        dirtyCellsLookupTable.resetAndInitialize(firstRowOffset, lastRowOffset, viewportSize.getColumns());
        dirtyCellsLookupTable.setDirty(cursorPosition);
        if(lastDrawnCursorPosition != null && !lastDrawnCursorPosition.equals(cursorPosition)) {
            if(virtualTerminal.getCharacter(lastDrawnCursorPosition).isDoubleWidth()) {
                dirtyCellsLookupTable.setDirty(lastDrawnCursorPosition.withRelativeColumn(1));
            }
            if(lastDrawnCursorPosition.getColumn() > 0 && virtualTerminal.getCharacter(lastDrawnCursorPosition.withRelativeColumn(-1)).isDoubleWidth()) {
                dirtyCellsLookupTable.setDirty(lastDrawnCursorPosition.withRelativeColumn(-1));
            }
            dirtyCellsLookupTable.setDirty(lastDrawnCursorPosition);
        }

        TreeSet<TerminalPosition> dirtyCells = virtualTerminal.getAndResetDirtyCells();
        for(TerminalPosition position: dirtyCells) {
            dirtyCellsLookupTable.setDirty(position);
        }
    }

    private void ensureGraphicBufferHasRightSize() {
        if(backbuffer == null) {
            backbuffer = new BufferedImage(getWidth() * 2, getHeight() * 2, BufferedImage.TYPE_INT_RGB);
            copybuffer = new BufferedImage(getWidth() * 2, getHeight() * 2, BufferedImage.TYPE_INT_RGB);

            // We only need to set the content of the backbuffer during initialization time
            Graphics2D graphics = backbuffer.createGraphics();
            graphics.setColor(colorConfiguration.toAWTColor(TextColor.ANSI.DEFAULT, false, false));
            graphics.fillRect(0, 0, getWidth() * 2, getHeight() * 2);
            graphics.dispose();
        }
        if(backbuffer.getWidth() < getWidth() || backbuffer.getWidth() > getWidth() * 4 ||
                backbuffer.getHeight() < getHeight() || backbuffer.getHeight() > getHeight() * 4) {

            BufferedImage newBackbuffer = new BufferedImage(Math.max(getWidth(), 1) * 2, Math.max(getHeight(), 1) * 2, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = newBackbuffer.createGraphics();
            graphics.fillRect(0, 0, newBackbuffer.getWidth(), newBackbuffer.getHeight());
            graphics.drawImage(backbuffer, 0, 0, null);
            graphics.dispose();
            backbuffer = newBackbuffer;

            // Re-initialize the copy buffer, but we don't need to set any content
            copybuffer = new BufferedImage(Math.max(getWidth(), 1) * 2, Math.max(getHeight(), 1) * 2, BufferedImage.TYPE_INT_RGB);
        }
    }

    private void drawCharacter(
            Graphics g,
            TextCharacter character,
            int columnIndex,
            int rowIndex,
            Color foregroundColor,
            Color backgroundColor,
            int fontWidth,
            int fontHeight,
            int characterWidth,
            int scrollingOffsetInPixels,
            boolean drawCursor) {

        int x = columnIndex * fontWidth;
        int y = rowIndex * fontHeight - scrollingOffsetInPixels;
        g.setColor(backgroundColor);
        g.setClip(x, y, characterWidth, fontHeight);
        g.fillRect(x, y, characterWidth, fontHeight);

        g.setColor(foregroundColor);
        Font font = getFontForCharacter(character);
        g.setFont(font);
        FontMetrics fontMetrics = g.getFontMetrics();
        g.drawString(Character.toString(character.getCharacter()), x, y + fontHeight - fontMetrics.getDescent() + 1);

        if(character.isCrossedOut()) {
            int lineStartX = x;
            int lineStartY = y + (fontHeight / 2);
            int lineEndX = lineStartX + characterWidth;
            g.drawLine(lineStartX, lineStartY, lineEndX, lineStartY);
        }
        if(character.isUnderlined()) {
            int lineStartX = x;
            int lineStartY = y + fontHeight - fontMetrics.getDescent() + 1;
            int lineEndX = lineStartX + characterWidth;
            g.drawLine(lineStartX, lineStartY, lineEndX, lineStartY);
        }

        if(drawCursor) {
            if(deviceConfiguration.getCursorColor() == null) {
                g.setColor(foregroundColor);
            }
            else {
                g.setColor(colorConfiguration.toAWTColor(deviceConfiguration.getCursorColor(), false, false));
            }
            if(deviceConfiguration.getCursorStyle() == TerminalEmulatorDeviceConfiguration.CursorStyle.UNDER_BAR) {
                g.fillRect(x, y + fontHeight - 3, characterWidth, 2);
            }
            else if(deviceConfiguration.getCursorStyle() == TerminalEmulatorDeviceConfiguration.CursorStyle.VERTICAL_BAR) {
                g.fillRect(x, y + 1, 2, fontHeight - 2);
            }
        }
    }


    private Color deriveTrueForegroundColor(TextCharacter character, boolean atCursorLocation) {
        TextColor foregroundColor = character.getForegroundColor();
        TextColor backgroundColor = character.getBackgroundColor();
        boolean reverse = character.isReversed();
        boolean blink = character.isBlinking();

        if(cursorIsVisible && atCursorLocation) {
            if(deviceConfiguration.getCursorStyle() == TerminalEmulatorDeviceConfiguration.CursorStyle.REVERSED &&
                    (!deviceConfiguration.isCursorBlinking() || !blinkOn)) {
                reverse = true;
            }
        }

        if(reverse && (!blink || !blinkOn)) {
            return colorConfiguration.toAWTColor(backgroundColor, backgroundColor != TextColor.ANSI.DEFAULT, character.isBold());
        }
        else if(!reverse && blink && blinkOn) {
            return colorConfiguration.toAWTColor(backgroundColor, false, character.isBold());
        }
        else {
            return colorConfiguration.toAWTColor(foregroundColor, true, character.isBold());
        }
    }

    private Color deriveTrueBackgroundColor(TextCharacter character, boolean atCursorLocation) {
        TextColor foregroundColor = character.getForegroundColor();
        TextColor backgroundColor = character.getBackgroundColor();
        boolean reverse = character.isReversed();

        if(cursorIsVisible && atCursorLocation) {
            if(deviceConfiguration.getCursorStyle() == TerminalEmulatorDeviceConfiguration.CursorStyle.REVERSED &&
                    (!deviceConfiguration.isCursorBlinking() || !blinkOn)) {
                reverse = true;
            }
            else if(deviceConfiguration.getCursorStyle() == TerminalEmulatorDeviceConfiguration.CursorStyle.FIXED_BACKGROUND) {
                backgroundColor = deviceConfiguration.getCursorColor();
            }
        }

        if(reverse) {
            return colorConfiguration.toAWTColor(foregroundColor, backgroundColor == TextColor.ANSI.DEFAULT, character.isBold());
        }
        else {
            return colorConfiguration.toAWTColor(backgroundColor, false, false);
        }
    }

    ///////////
    // Then delegate all Terminal interface methods to the virtual terminal implementation
    //
    // Some of these methods we need to pass to the AWT-thread, which makes the call asynchronous. Hopefully this isn't
    // causing too much problem...
    ///////////
    @Override
    public KeyStroke pollInput() {
        return keyQueue.poll();
    }

    @Override
    public KeyStroke readInput() throws IOException {
        try {
            return keyQueue.take();
        }
        catch(InterruptedException ignore) {
            throw new IOException("Blocking input was interrupted");
        }
    }

    @Override
    public synchronized void enterPrivateMode() {
        virtualTerminal.switchToPrivateMode();
        clearBackBufferAndVisualState();
        flush();
    }

    @Override
    public synchronized void exitPrivateMode() {
        virtualTerminal.switchToNormalMode();
        clearBackBufferAndVisualState();
        flush();
    }

    @Override
    public synchronized void clearScreen() {
        virtualTerminal.clear();
        clearBackBufferAndVisualState();
        flush();
    }

    /**
     * Clears out the back buffer and the resets the visual state so next paint operation will do a full repaint of
     * everything
     */
    void clearBackBufferAndVisualState() {
        // Manually clear the backbuffer and visual state
        if(backbuffer != null) {
            Graphics2D graphics = backbuffer.createGraphics();
            Color backgroundColor = colorConfiguration.toAWTColor(TextColor.ANSI.DEFAULT, false, false);
            graphics.setColor(backgroundColor);
            graphics.fillRect(0, 0, getWidth(), getHeight());
            graphics.dispose();
        }
    }

    @Override
    public synchronized void setCursorPosition(final int x, final int y) {
        virtualTerminal.setCursorPosition(new TerminalPosition(x, y));
    }

    @Override
    public void setCursorVisible(final boolean visible) {
        cursorIsVisible = visible;
    }

    @Override
    public synchronized void putCharacter(final char c) {
        virtualTerminal.putCharacter(new TextCharacter(c, foregroundColor, backgroundColor, activeSGRs));
    }

    @Override
    public TextGraphics newTextGraphics() throws IOException {
        return new VirtualTerminalTextGraphics(virtualTerminal);
    }

    @Override
    public void enableSGR(final SGR sgr) {
        activeSGRs.add(sgr);
    }

    @Override
    public void disableSGR(final SGR sgr) {
        activeSGRs.remove(sgr);
    }

    @Override
    public void resetColorAndSGR() {
        foregroundColor = TextColor.ANSI.DEFAULT;
        backgroundColor = TextColor.ANSI.DEFAULT;
        activeSGRs.clear();
    }

    @Override
    public void setForegroundColor(final TextColor color) {
        foregroundColor = color;
    }

    @Override
    public void setBackgroundColor(final TextColor color) {
        backgroundColor = color;
    }

    @Override
    public synchronized TerminalSize getTerminalSize() {
        return virtualTerminal.getViewportSize();
    }

    @Override
    public byte[] enquireTerminal(int timeout, TimeUnit timeoutUnit) {
        return enquiryString.getBytes();
    }

    @Override
    public synchronized void flush() {
        updateBackBuffer(scrollController.getScrollingOffset());
        repaint();
    }

    @Override
    public void addResizeListener(ResizeListener listener) {
        resizeListeners.add(listener);
    }

    @Override
    public void removeResizeListener(ResizeListener listener) {
        resizeListeners.remove(listener);
    }

    ///////////
    // Remaining are private internal classes used by SwingTerminal
    ///////////
    private static final Set<Character> TYPED_KEYS_TO_IGNORE = new HashSet<Character>(Arrays.asList('\n', '\t', '\r', '\b', '\33', (char)127));

    /**
     * Class that translates AWT key events into Lanterna {@link KeyStroke}
     */
    protected class TerminalInputListener extends KeyAdapter {
        @Override
        public void keyTyped(KeyEvent e) {
            char character = e.getKeyChar();
            boolean altDown = (e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0;
            boolean ctrlDown = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;

            if(!TYPED_KEYS_TO_IGNORE.contains(character)) {
                if(ctrlDown) {
                    //We need to re-adjust the character if ctrl is pressed, just like for the AnsiTerminal
                    character = (char) ('a' - 1 + character);
                }
                keyQueue.add(new KeyStroke(character, ctrlDown, altDown));
            }
        }

        @Override
        public void keyPressed(KeyEvent e) {
            boolean altDown = (e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0;
            boolean ctrlDown = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
            if(e.getKeyCode() == KeyEvent.VK_ENTER) {
                keyQueue.add(new KeyStroke(KeyType.Enter, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                keyQueue.add(new KeyStroke(KeyType.Escape, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                keyQueue.add(new KeyStroke(KeyType.Backspace, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_LEFT) {
                keyQueue.add(new KeyStroke(KeyType.ArrowLeft, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_RIGHT) {
                keyQueue.add(new KeyStroke(KeyType.ArrowRight, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_UP) {
                keyQueue.add(new KeyStroke(KeyType.ArrowUp, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_DOWN) {
                keyQueue.add(new KeyStroke(KeyType.ArrowDown, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_INSERT) {
                keyQueue.add(new KeyStroke(KeyType.Insert, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_DELETE) {
                keyQueue.add(new KeyStroke(KeyType.Delete, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_HOME) {
                keyQueue.add(new KeyStroke(KeyType.Home, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_END) {
                keyQueue.add(new KeyStroke(KeyType.End, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_PAGE_UP) {
                keyQueue.add(new KeyStroke(KeyType.PageUp, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_PAGE_DOWN) {
                keyQueue.add(new KeyStroke(KeyType.PageDown, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_F1) {
                keyQueue.add(new KeyStroke(KeyType.F1, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_F2) {
                keyQueue.add(new KeyStroke(KeyType.F2, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_F3) {
                keyQueue.add(new KeyStroke(KeyType.F3, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_F4) {
                keyQueue.add(new KeyStroke(KeyType.F4, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_F5) {
                keyQueue.add(new KeyStroke(KeyType.F5, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_F6) {
                keyQueue.add(new KeyStroke(KeyType.F6, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_F7) {
                keyQueue.add(new KeyStroke(KeyType.F7, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_F8) {
                keyQueue.add(new KeyStroke(KeyType.F8, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_F9) {
                keyQueue.add(new KeyStroke(KeyType.F9, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_F10) {
                keyQueue.add(new KeyStroke(KeyType.F10, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_F11) {
                keyQueue.add(new KeyStroke(KeyType.F11, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_F12) {
                keyQueue.add(new KeyStroke(KeyType.F12, ctrlDown, altDown));
            }
            else if(e.getKeyCode() == KeyEvent.VK_TAB) {
                if(e.isShiftDown()) {
                    keyQueue.add(new KeyStroke(KeyType.ReverseTab, ctrlDown, altDown));
                }
                else {
                    keyQueue.add(new KeyStroke(KeyType.Tab, ctrlDown, altDown));
                }
            }
            else {
                //keyTyped doesn't catch this scenario (for whatever reason...) so we have to do it here
                if(altDown && ctrlDown && e.getKeyCode() >= 'A' && e.getKeyCode() <= 'Z') {
                    char asLowerCase = Character.toLowerCase((char) e.getKeyCode());
                    keyQueue.add(new KeyStroke(asLowerCase, true, true));
                }
            }
        }
    }

    private static class DirtyCellsLookupTable {
        private final List<byte[]> table;
        private int firstRowIndex;
        private boolean allDirty;

        DirtyCellsLookupTable() {
            table = new ArrayList<byte[]>();
            firstRowIndex = -1;
            allDirty = false;
        }

        void resetAndInitialize(int firstRowIndex, int lastRowIndex, int columns) {
            this.firstRowIndex = firstRowIndex;
            this.allDirty = false;
            int rows = lastRowIndex - firstRowIndex + 1;
            while(table.size() < rows) {
                table.add(new byte[columns]);
            }
            while(table.size() > rows) {
                table.remove(table.size() - 1);
            }
            for(int index = 0; index < table.size(); index++) {
                if(table.get(index).length != columns) {
                    table.set(index, new byte[columns]);
                }
                else {
                    Arrays.fill(table.get(index), (byte)0);
                }
            }
        }

        public void setAllDirty() {
            allDirty = true;
        }

        public boolean isAllDirty() {
            return allDirty;
        }

        public void setDirty(TerminalPosition position) {
            if(position.getRow() < firstRowIndex ||
                    position.getRow() >= firstRowIndex + table.size()) {
                return;
            }
            byte[] tableRow = table.get(position.getRow() - firstRowIndex);
            if(position.getColumn() < tableRow.length) {
                tableRow[position.getColumn()] = (byte)1;
            }
        }

        public void setRowDirty(int rowNumber) {
            Arrays.fill(table.get(rowNumber - firstRowIndex), (byte)1);
        }

        public void setColumnDirty(int column) {
            for(byte[] row: table) {
                if(column < row.length) {
                    row[column] = (byte)1;
                }
            }
        }

        public boolean isDirty(int row, int column) {
            if(row < firstRowIndex || row >= firstRowIndex + table.size()) {
                return false;
            }
            byte[] tableRow = table.get(row - firstRowIndex);
            if(column < tableRow.length) {
                return tableRow[column] == (byte)1;
            }
            else {
                return false;
            }
        }
    }
}