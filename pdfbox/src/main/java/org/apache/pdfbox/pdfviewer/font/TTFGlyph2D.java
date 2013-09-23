/*

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package org.apache.pdfbox.pdfviewer.font;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.ttf.CMAPEncodingEntry;
import org.apache.fontbox.ttf.CMAPTable;
import org.apache.fontbox.ttf.GlyfDescript;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.GlyphDescription;
import org.apache.fontbox.ttf.HeaderTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.encoding.Encoding;
import org.apache.pdfbox.encoding.MacOSRomanEncoding;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2Font;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * This class provides a glyph to GeneralPath conversion for true type fonts.
 * 
 * This class is based on code from Apache Batik a subproject of Apache XMLGraphics. see
 * http://xmlgraphics.apache.org/batik/ for further details.
 */
public class TTFGlyph2D implements Glyph2D
{

    /**
     * Log instance.
     */
    private static final Log LOG = LogFactory.getLog(TTFGlyph2D.class);

    /**
     * Default scaling value.
     */
    private static final float DEFAULT_SCALING = 0.001f;

    /**
     * Start of coderanges.
     */
    private static final int START_RANGE_F000 = 0xF000;
    private static final int START_RANGE_F100 = 0xF100;
    private static final int START_RANGE_F200 = 0xF200;

    private TrueTypeFont font;
    private PDCIDFontType2Font descendantFont;
    private String name;
    private float scale;
    private CMAPEncodingEntry cmapWinUnicode = null;
    private CMAPEncodingEntry cmapWinSymbol = null;
    private CMAPEncodingEntry cmapMacintoshSymbol = null;
    private boolean isSymbol = false;
    private Map<Integer, GeneralPath> glyphs = new HashMap<Integer, GeneralPath>();
    private Encoding fontEncoding = null;
    private CMap fontCMap = null;
    private boolean isCIDFont = false;
    private boolean hasIdentityCIDMapping = false;
    private boolean hasCID2GIDMapping = false;
    private boolean hasTwoByteMappings = false;

    /**
     * Constructor.
     * 
     * @param trueTypeFont the true type font containing the glyphs
     * @param pdFont the given PDFont
     */
    public TTFGlyph2D(TrueTypeFont trueTypeFont, PDFont pdFont)
    {
        this(trueTypeFont, pdFont, null);
    }

    /**
     * Constructor.
     * 
     * @param trueTypeFont the true type font containing the glyphs
     * @param pdFont the given PDFont
     * @param descFont the descendant font of a Type0Font
     */
    public TTFGlyph2D(TrueTypeFont trueTypeFont, PDFont pdFont, PDCIDFontType2Font descFont)
    {
        font = trueTypeFont;
        // get units per em, which is used as scaling factor
        HeaderTable header = font.getHeader();
        if (header != null)
        {
            scale = 1f / header.getUnitsPerEm();
        }
        else
        {
            scale = DEFAULT_SCALING;
        }
        extractCMaps();
        extractFontSpecifics(pdFont, descFont);
    }

    /**
     * extract all useful CMaps.
     */
    private void extractCMaps()
    {
        CMAPTable cmapTable = font.getCMAP();
        if (cmapTable != null)
        {
            // get all relevant CMaps
            CMAPEncodingEntry[] cmaps = cmapTable.getCmaps();
            for (int i = 0; i < cmaps.length; i++)
            {
                if (CMAPTable.PLATFORM_WINDOWS == cmaps[i].getPlatformId())
                {
                    if (CMAPTable.ENCODING_UNICODE == cmaps[i].getPlatformEncodingId())
                    {
                        cmapWinUnicode = cmaps[i];
                    }
                    else if (CMAPTable.ENCODING_SYMBOL == cmaps[i].getPlatformEncodingId())
                    {
                        cmapWinSymbol = cmaps[i];
                    }
                }
                else if (CMAPTable.PLATFORM_MACINTOSH == cmaps[i].getPlatformId())
                {
                    if (CMAPTable.ENCODING_SYMBOL == cmaps[i].getPlatformEncodingId())
                    {
                        cmapMacintoshSymbol = cmaps[i];
                    }
                }
            }
        }

    }

    /**
     * Extract all font specific information.
     * 
     * @param pdFont the given PDFont
     */
    private void extractFontSpecifics(PDFont pdFont, PDCIDFontType2Font descFont)
    {
        isSymbol = pdFont.isSymbolicFont();
        name = pdFont.getBaseFont();
        fontEncoding = pdFont.getFontEncoding();
        if (descFont != null)
        {
            isCIDFont = true;
            descendantFont = descFont;
            hasIdentityCIDMapping = descendantFont.hasIdentityCIDToGIDMap();
            hasCID2GIDMapping = descendantFont.hasCIDToGIDMap();
            fontCMap = pdFont.getCMap();
            if (fontCMap != null)
            {
                hasTwoByteMappings = fontCMap.hasTwoByteMappings();
            }

        }
    }

    /**
     * Set the points of a glyph from the GlyphDescription.
     */
    private Point[] describe(GlyphDescription gd)
    {
        int endPtIndex = 0;
        Point[] points = new Point[gd.getPointCount()];
        for (int i = 0; i < gd.getPointCount(); i++)
        {
            boolean endPt = gd.getEndPtOfContours(endPtIndex) == i;
            if (endPt)
            {
                endPtIndex++;
            }
            points[i] = new Point(gd.getXCoordinate(i), -gd.getYCoordinate(i),
                    (gd.getFlags(i) & GlyfDescript.ON_CURVE) != 0, endPt);
        }
        return points;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GeneralPath getPathForGlyphId(int glyphId)
    {
        GeneralPath glyphPath = null;
        if (glyphs.containsKey(glyphId))
        {
            glyphPath = glyphs.get(glyphId);
        }
        else
        {
            GlyphData[] glyphData = font.getGlyph().getGlyphs();
            if (glyphId < glyphData.length && glyphData[glyphId] != null)
            {
                GlyphData glyph = glyphData[glyphId];
                GlyphDescription gd = glyph.getDescription();
                Point[] points = describe(gd);
                glyphPath = calculatePath(points);
                AffineTransform atScale = AffineTransform.getScaleInstance(scale, scale);
                glyphPath.transform(atScale);
                glyphs.put(glyphId, glyphPath);
            }
            else
            {
                LOG.debug(name + ": Glyph not found:" + glyphId);
            }
        }
        return glyphPath != null ? (GeneralPath) glyphPath.clone() : null;
    }

    /*
     * Try to map the given code to the corresponding glyph-ID
     */
    private int getGlyphcode(int code)
    {
        if (isCIDFont)
        {
            return getGID(code);
        }

        int result = 0;
        if (fontEncoding != null && !isSymbol)
        {
            try
            {
                String charactername = fontEncoding.getName(code);
                if (charactername != null)
                {
                    if (cmapWinUnicode != null)
                    {
                        String unicode = Encoding.getCharacterForName(charactername);
                        if (unicode != null)
                        {
                            result = unicode.codePointAt(0);
                        }
                        result = cmapWinUnicode.getGlyphId(result);
                    }
                    else if (cmapMacintoshSymbol != null)
                    {
                        result = MacOSRomanEncoding.INSTANCE.getCode(charactername);
                        result = cmapMacintoshSymbol.getGlyphId(result);
                    }
                }
            }
            catch (IOException exception)
            {
                LOG.error("Caught an exception getGlyhcode: " + exception);
            }
        }
        if (fontEncoding == null || isSymbol)
        {
            if (cmapWinSymbol != null)
            {
                result = cmapWinSymbol.getGlyphId(code);
                if (code >= 0 && code <= 0xFF)
                {
                    // the CMap may use one of the following code ranges,
                    // so that we have to add the high byte to get the
                    // mapped value
                    if (result == 0)
                    {
                        // F000 - F0FF
                        result = cmapWinSymbol.getGlyphId(code + START_RANGE_F000);
                    }
                    if (result == 0)
                    {
                        // F100 - F1FF
                        result = cmapWinSymbol.getGlyphId(code + START_RANGE_F100);
                    }
                    if (result == 0)
                    {
                        // F200 - F2FF
                        result = cmapWinSymbol.getGlyphId(code + START_RANGE_F200);
                    }
                }
            }
            else if (cmapMacintoshSymbol != null)
            {
                result = cmapMacintoshSymbol.getGlyphId(code);
            }
        }
        return result;
    }

    /**
     * Get the GID for the given CIDFont.
     * 
     * @param code the given CID
     * @return the mapped GID
     */
    private int getGID(int code)
    {
        if (hasIdentityCIDMapping)
        {
            // identity mapping
            return code;
        }
        if (hasCID2GIDMapping)
        {
            // use the provided CID2GID mapping
            return descendantFont.mapCIDToGID(code);
        }
        if (fontCMap != null)
        {
            String string = fontCMap.lookup(code, hasTwoByteMappings ? 2 : 1);
            if (string != null)
            {
                return string.codePointAt(0);
            }
        }
        return code;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GeneralPath getPathForCharactercode(int code)
    {

        int glyphId = getGlyphcode(code);

        if (glyphId > 0)
        {
            return getPathForGlyphId(glyphId);
        }
        glyphId = code;
        // there isn't any mapping, but probably an optional CMap
        if (fontCMap != null)
        {
            String string = fontCMap.lookup(code, hasTwoByteMappings ? 2 : 1);
            if (string != null)
            {
                glyphId = string.codePointAt(0);
            }
        }
        return getPathForGlyphId(glyphId);
    }

    /**
     * Use the given points to calculate a GeneralPath.
     * 
     * @param points the points to be used to generate the GeneralPath
     * 
     * @return the calculated GeneralPath
     */
    private GeneralPath calculatePath(Point[] points)
    {
        GeneralPath path = new GeneralPath();
        int numberOfPoints = points.length;
        int i = 0;
        boolean endOfContour = true;
        Point startingPoint = null;
        Point lastCtrlPoint = null;
        while (i < numberOfPoints)
        {
            Point point = points[i % numberOfPoints];
            Point nextPoint1 = points[(i + 1) % numberOfPoints];
            Point nextPoint2 = points[(i + 2) % numberOfPoints];
            // new contour
            if (endOfContour)
            {
                // skip endOfContour points
                if (point.endOfContour)
                {
                    i++;
                    continue;
                }
                // move to the starting point
                path.moveTo(point.x, point.y);
                endOfContour = false;
                startingPoint = point;
            }
            // lineTo
            if (point.onCurve && nextPoint1.onCurve)
            {
                path.lineTo(nextPoint1.x, nextPoint1.y);
                i++;
                if (point.endOfContour || nextPoint1.endOfContour)
                {
                    endOfContour = true;
                    path.closePath();
                }
                continue;
            }
            // quadratic bezier
            if (point.onCurve && !nextPoint1.onCurve && nextPoint2.onCurve)
            {
                if (nextPoint1.endOfContour)
                {
                    // use the starting point as end point
                    path.quadTo(nextPoint1.x, nextPoint1.y, startingPoint.x, startingPoint.y);
                }
                else
                {
                    path.quadTo(nextPoint1.x, nextPoint1.y, nextPoint2.x, nextPoint2.y);
                }
                if (nextPoint1.endOfContour || nextPoint2.endOfContour)
                {
                    endOfContour = true;
                    path.closePath();
                }
                i += 2;
                lastCtrlPoint = nextPoint1;
                continue;
            }
            if (point.onCurve && !nextPoint1.onCurve && !nextPoint2.onCurve)
            {
                // interpolate endPoint
                int endPointX = midValue(nextPoint1.x, nextPoint2.x);
                int endPointY = midValue(nextPoint1.y, nextPoint2.y);
                path.quadTo(nextPoint1.x, nextPoint1.y, endPointX, endPointY);
                if (point.endOfContour || nextPoint1.endOfContour || nextPoint2.endOfContour)
                {
                    path.quadTo(nextPoint2.x, nextPoint2.y, startingPoint.x, startingPoint.y);
                    endOfContour = true;
                    path.closePath();
                }
                i += 2;
                lastCtrlPoint = nextPoint1;
                continue;
            }
            if (!point.onCurve && !nextPoint1.onCurve)
            {
                Point2D lastEndPoint = path.getCurrentPoint();
                // calculate new control point using the previous control point
                lastCtrlPoint = new Point(midValue(lastCtrlPoint.x, (int) lastEndPoint.getX()), midValue(
                        lastCtrlPoint.y, (int) lastEndPoint.getY()));
                // interpolate endPoint
                int endPointX = midValue((int) lastEndPoint.getX(), nextPoint1.x);
                int endPointY = midValue((int) lastEndPoint.getY(), nextPoint1.y);
                path.quadTo(lastCtrlPoint.x, lastCtrlPoint.y, endPointX, endPointY);
                if (point.endOfContour || nextPoint1.endOfContour)
                {
                    endOfContour = true;
                    path.closePath();
                }
                i++;
                continue;
            }
            if (!point.onCurve && nextPoint1.onCurve)
            {
                path.quadTo(point.x, point.y, nextPoint1.x, nextPoint1.y);
                if (point.endOfContour || nextPoint1.endOfContour)
                {
                    endOfContour = true;
                    path.closePath();
                }
                i++;
                lastCtrlPoint = point;
                continue;
            }
            LOG.error("Unknown glyph command!!");
            break;
        }
        return path;
    }

    private int midValue(int a, int b)
    {
        return a + (b - a) / 2;
    }

    /**
     * This class represents one point of a glyph.
     * 
     */
    private class Point
    {

        private int x = 0;
        private int y = 0;
        private boolean onCurve = true;
        private boolean endOfContour = false;

        public Point(int xValue, int yValue, boolean onCurveValue, boolean endOfContourValue)
        {
            x = xValue;
            y = yValue;
            onCurve = onCurveValue;
            endOfContour = endOfContourValue;
        }

        public Point(int xValue, int yValue)
        {
            this(xValue, yValue, false, false);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfGlyphs()
    {
        return font != null ? font.getGlyph().getGlyphs().length : 0;
    }

    @Override
    public void dispose()
    {
        cmapMacintoshSymbol = null;
        cmapWinSymbol = null;
        cmapWinUnicode = null;
        font = null;
        descendantFont = null;
        fontCMap = null;
        fontEncoding = null;
        if (glyphs != null)
        {
            glyphs.clear();
        }
    }
}
