package com.lm.routing.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HaversineMatrixServiceTest {

    private final HaversineMatrixService service = new HaversineMatrixService();

    @Test
    void haversineDistance_shouldReturnZeroForSamePoint() {
        GeoPoint p = new GeoPoint(31.2304, 121.4737);
        double d = HaversineMatrixService.haversineDistance(p, p);
        assertEquals(0.0, d, 0.01);
    }

    @Test
    void haversineDistance_shouldMatchKnownDistance() {
        // Shanghai Pudong → Shanghai Hongqiao: ~30km straight-line
        GeoPoint pudong = new GeoPoint(31.2304, 121.4737);
        GeoPoint hongqiao = new GeoPoint(31.1970, 121.3360);

        double d = HaversineMatrixService.haversineDistance(pudong, hongqiao);

        // Should be roughly 13-15 km straight-line
        assertTrue(d > 10000, "Distance should be > 10km");
        assertTrue(d < 20000, "Distance should be < 20km");
    }

    @Test
    void haversineDistance_shouldBeSymmetric() {
        GeoPoint a = new GeoPoint(31.23, 121.47);
        GeoPoint b = new GeoPoint(31.50, 121.80);

        double d1 = HaversineMatrixService.haversineDistance(a, b);
        double d2 = HaversineMatrixService.haversineDistance(b, a);

        assertEquals(d1, d2, 0.01);
    }

    @Test
    void buildMatrix_shouldBeSymmetric() {
        List<GeoPoint> points = List.of(
                new GeoPoint(31.23, 121.47),
                new GeoPoint(31.25, 121.50),
                new GeoPoint(31.20, 121.45),
                new GeoPoint(31.28, 121.55),
                new GeoPoint(31.22, 121.48)
        );

        double[][] matrix = service.buildMatrix(points);

        assertEquals(5, matrix.length);
        for (int i = 0; i < 5; i++) {
            assertEquals(0.0, matrix[i][i], 0.01, "Diagonal should be 0");
            for (int j = 0; j < 5; j++) {
                assertEquals(matrix[i][j], matrix[j][i], 0.01,
                        "Matrix should be symmetric at [" + i + "][" + j + "]");
            }
        }
    }

    @Test
    void buildMatrix_emptyList_shouldReturnEmptyMatrix() {
        List<GeoPoint> points = List.of();
        double[][] matrix = service.buildMatrix(points);
        assertEquals(0, matrix.length);
    }

    @Test
    void buildMatrix_singlePoint_shouldReturn1x1Zero() {
        List<GeoPoint> points = List.of(new GeoPoint(31.23, 121.47));
        double[][] matrix = service.buildMatrix(points);
        assertEquals(1, matrix.length);
        assertEquals(1, matrix[0].length);
        assertEquals(0.0, matrix[0][0], 0.01);
    }

    @Test
    void haversineDistance_antipodalPoints_shouldReturnHalfEarthCircumference() {
        // Two points on opposite sides of the Earth
        GeoPoint a = new GeoPoint(0.0, 0.0);       // equator, prime meridian
        GeoPoint b = new GeoPoint(0.0, 180.0);     // equator, date line

        double d = HaversineMatrixService.haversineDistance(a, b);

        // Half the Earth's circumference ≈ 20,000 km
        double halfCircumference = Math.PI * 6_371_000.0;
        assertEquals(halfCircumference, d, 1000); // tolerance ~1km due to rounding
    }

    @Test
    void haversineDistance_northPoleToSouthPole_shouldReturnHalfCircumference() {
        GeoPoint north = new GeoPoint(90.0, 0.0);
        GeoPoint south = new GeoPoint(-90.0, 0.0);

        double d = HaversineMatrixService.haversineDistance(north, south);

        double halfCircumference = Math.PI * 6_371_000.0;
        assertEquals(halfCircumference, d, 1000);
    }

    @Test
    void buildMatrixFromArrays_shouldMatchBuildMatrix() {
        double[] lats = {31.23, 31.25, 31.20};
        double[] lngs = {121.47, 121.50, 121.45};

        double[][] fromArrays = service.buildMatrixFromArrays(lats, lngs);

        List<GeoPoint> points = List.of(
                new GeoPoint(31.23, 121.47),
                new GeoPoint(31.25, 121.50),
                new GeoPoint(31.20, 121.45)
        );
        double[][] fromList = service.buildMatrix(points);

        assertEquals(fromList.length, fromArrays.length);
        for (int i = 0; i < fromList.length; i++) {
            assertArrayEquals(fromList[i], fromArrays[i], 0.01,
                    "Row " + i + " should match");
        }
    }

    @Test
    void buildMatrixFromArrays_mismatchedLengths_shouldThrowException() {
        double[] lats = {31.23, 31.25};
        double[] lngs = {121.47, 121.50, 121.45}; // one extra

        assertThrows(IllegalArgumentException.class,
                () -> service.buildMatrixFromArrays(lats, lngs));
    }

    @Test
    void buildMatrixFromArrays_empty_shouldReturnEmpty() {
        double[] lats = {};
        double[] lngs = {};
        double[][] matrix = service.buildMatrixFromArrays(lats, lngs);
        assertEquals(0, matrix.length);
    }
}
