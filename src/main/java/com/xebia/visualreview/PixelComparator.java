/*
 * Copyright 2015 Xebia B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xebia.visualreview;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.io.File;
import java.io.IOException;
import java.lang.Math;
import org.json.JSONObject;

import clojure.lang.Keyword;


public class PixelComparator {

    private static int DIFFCOLOUR = (128<<24) & 0xff000000|       //a
            (128<<16) &   0xff0000|       //r
            (0  <<8 ) &     0xff00|       //g
            (0  <<0 ) &       0xff;       //b

    private static class Rect{
        private int x;
        private int y;
        private int width;
        private int height;
        public boolean isValid;
        Rect(int x,int y,int width, int height){
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            isValid = true;
        }
        Rect(java.util.Map info, int maxWidth, int maxHeight){
            isValid = true;
            try{
                int x = Integer.parseInt(info.get(Keyword.find("x")).toString());
                int y = Integer.parseInt(info.get(Keyword.find("y")).toString());
                int width = Integer.parseInt(info.get(Keyword.find("width")).toString());
                int height = Integer.parseInt(info.get(Keyword.find("height")).toString());

                this.x = Math.min(Math.max(0,x),maxWidth);
                this.y = Math.max(Math.min(this.y,maxHeight),y);
                this.width = Math.min(Math.max(0,width),maxWidth-this.x);
                this.height = Math.min(Math.max(0,height),maxHeight-this.y);
            }catch (Exception e) {
                System.out.println("Invalid rect "+info);
                isValid = false;
                throw new RuntimeException(new Exception("Invalid Rect "+info));
            }
        }

        void applyToImage(BufferedImage image,int rgb){
            if (isValid){
                for(int i = x;i<x+width;i++){
                    for(int j = y;j<y+height;j++){
                        image.setRGB(i,j,rgb);
                    }
                }
            }
        }
    }

    private static BufferedImage generateMask(java.util.Map maskInfo, int width, int height){
        BufferedImage maskImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Rect fullRect = new Rect(0,0,width,height);
        if (maskInfo != null) {
            java.util.List<java.util.Map> excludeszones = (java.util.List<java.util.Map>)maskInfo.get(Keyword.find("excludeZones"));
            if (excludeszones!=null) {
                for (int i = 0; i < excludeszones.size(); i++) {
                    java.util.Map excludeZone = excludeszones.get(i);
                    Rect rect = new Rect(excludeZone, width, height);
                    rect.applyToImage(maskImage, DIFFCOLOUR);
                }
            }
        }
        return maskImage;
    }

    public static DiffReport processImage(File beforeFile, File afterFile ,java.util.Map maskInfo, String compareSettings) {
        try {
            int precision = getIntValue(compareSettings,"precision");
            if (precision < 0 || precision > 255) {
                throw new RuntimeException("VisualReview only supports precision values between 0 and 255");
            }
            boolean antialiasing = getBoolValue(compareSettings,"anti-aliasing");

            PixelGrabber beforeGrab = grabImage(beforeFile);
            PixelGrabber afterGrab = grabImage(afterFile);

            int[] beforeData = null;
            int[] afterData = null;
            int beforeWidth = 0;
            int afterWidth = 0;
            int y1 = 0;
            int y2 = 0;

            if (beforeGrab.grabPixels()) {
                beforeWidth = beforeGrab.getWidth();
                y1 = beforeGrab.getHeight();
                beforeData = (int[]) beforeGrab.getPixels();
            }

            if (afterGrab.grabPixels()) {
                afterWidth = afterGrab.getWidth();
                y2 = afterGrab.getHeight();
                afterData = (int[]) afterGrab.getPixels();
            }

            int minX = Math.min(beforeWidth, afterWidth);
            int minY = Math.min(y1, y2);
            int diffWidth = Math.max(beforeWidth, afterWidth);
            int diffHeight = Math.max(y1, y2);
            int[] diffData = new int[diffWidth * diffHeight];
            int differentPixels = 0;
            boolean hasMask =  (maskInfo != null) ;
            BufferedImage maskImage = generateMask(maskInfo,diffWidth,diffHeight);

            for (int y = 0; y < diffHeight; y++) {
                for (int x = 0; x < diffWidth; x++) {
                    if (maskImage.getRGB(x,y) != DIFFCOLOUR) {

                      if (x >= minX || y >= minY || beforeData[y * beforeWidth + x] != afterData[y * afterWidth + x]) {
                        if (hasDifference(beforeData[y * beforeWidth + x], afterData[y * afterWidth + x],precision)) {
                            if (antialiasing && !hasDiffAntiAliasing(afterData[y * afterWidth + x], beforeData, y, x, beforeWidth)) {
                              //ALL OK!
                            } else {
                              diffData[y * diffWidth + x] = DIFFCOLOUR;
                              differentPixels++;
                            }
                        }
                      }
                    }
                }
            }

            BufferedImage diffImage = new BufferedImage(diffWidth, diffHeight, BufferedImage.TYPE_INT_ARGB);
            diffImage.setRGB(0, 0, diffWidth, diffHeight, diffData, 0, diffWidth);

            DiffReport report = new DiffReport(beforeFile, afterFile, diffImage, differentPixels);
            if (hasMask){
                report.setMaskImage(maskImage);
            }
            return report;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean hasDiffAntiAliasing(int newPixel, int[] baseImage, int y, int x, int diffWidth) {
      int distance = 1;

      int similarSiblings = 0;
      int highContrastSiblings = 0;
      int diffHueSiblings = 0;

      for (int i = distance*-1; i <= distance; i++){
				for (int j = distance*-1; j <= distance; j++){
          if(i==0 && j==0){
						// ignore source pixel
					} else {
            int newY = y+i;
            int newX = x+j;
            int targetPixel = baseImage[newY * diffWidth + newX];

            if (!hasDifference(targetPixel, newPixel,127)) {
                similarSiblings++;
            }

            if (hasHighContrast(targetPixel, newPixel)) {
                highContrastSiblings++;
            }

            if (hasDiffHue(targetPixel, newPixel)) {
                diffHueSiblings++;
            }
          }
        }
      }

      if (similarSiblings < 3) {
        return true;
      } else if (highContrastSiblings > 1 || diffHueSiblings > 1) {
        return true;
      }

      return false;
    }

    public static BufferedImage loadImage(File file) {
        try {
            return ImageIO.read(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean hasDifference(int data1,int data2, int maxDifference) {
        if (data1 == data2) {
            return false;
        }
        Pixel pixel1 = getRGB(data1);
        Pixel pixel2 = getRGB(data2);

        return (Math.abs(pixel1.red-pixel2.red) > maxDifference ||
            Math.abs(pixel1.green-pixel2.green) > maxDifference ||
            Math.abs(pixel1.blue-pixel2.blue) > maxDifference);
    }

    private static boolean hasHighContrast(int data1, int data2) {
      Pixel pixel1 = getRGB(data1);
      Pixel pixel2 = getRGB(data2);
      double brightness1 = getBrightness(pixel1.red,pixel1.green,pixel1.blue);
      double brightness2 = getBrightness(pixel2.red,pixel2.green,pixel2.blue);

      return Math.abs(brightness1 - brightness2) > 200;
    }

    private static boolean hasDiffHue(int data1, int data2) {
      Pixel pixel1 = getRGB(data1);
      Pixel pixel2 = getRGB(data2);
      double hue1 = getHue(pixel1.red,pixel1.green,pixel1.blue);
      double hue2 = getHue(pixel2.red,pixel2.green,pixel2.blue);

      return (Math.abs(hue1 - hue2) > 0.7);
    }

    private static double getBrightness(int r,int g,int b) {
			return 0.3*r + 0.59*g + 0.11*b;
		}
    private static double getHue(int r, int g, int b) {
      double rr = (double)r / 255;
			double gg = (double)g / 255;
			double bb = (double)b / 255;
			double max = Math.max(r, Math.max(g, b));
      double min = Math.min(r, Math.min(g, b));
			double hh;
			double dd;

			if (max == min){
				hh = 0; // achromatic
			} else{
				dd = max - min;
        if (max == r) {
          hh = (gg - bb) / dd + (gg < bb ? 6 : 0);
        } else if (max == g) {
          hh = (bb - rr) / dd + 2;
        } else {
          hh = (rr - gg) / dd + 4;
        }

				hh /= 6;
			}

			return hh;
    }

    private static int getIntValue(String compareSettings, String name) {
        int value = 0;

        if (!compareSettings.equals(null) && !compareSettings.equals("null") && !compareSettings.equals("")) {
            try {
                JSONObject json = new JSONObject(compareSettings);
                if (json.has(name)) {
                    try {
                        value = json.getInt(name);
                    } catch (Exception e) {
                        throw new Exception(name + " has invalid value.\n" + e);
                    }
                }
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        }

        return value;
    }

    private static boolean getBoolValue(String compareSettings, String name) {
        boolean value = false;

        if (!compareSettings.equals(null) && !compareSettings.equals("null") && !compareSettings.equals("")) {
            try {
                JSONObject json = new JSONObject(compareSettings);
                if (json.has(name)) {
                    try {
                        value = json.getBoolean(name);
                    } catch (Exception e) {
                        throw new Exception(name + " has invalid value.\n" + e);
                    }
                }
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        }

        return value;
    }

    private static PixelGrabber grabImage(File file) {
        try {
            return new PixelGrabber(loadImage(file), 0, 0, -1, -1, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Pixel getRGB(int data) {
      int b1 = (data)&0xFF;
      int g1 = (data>>8)&0xFF;
      int r1 = (data>>16)&0xFF;

      Pixel pixel = new Pixel();

      pixel.red = r1;
      pixel.green = g1;
      pixel.blue = b1;

      return pixel;
    }

    private static class Pixel {
      int red;
      int green;
      int blue;
    }
}
