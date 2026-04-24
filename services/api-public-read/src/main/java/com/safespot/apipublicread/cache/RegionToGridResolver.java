package com.safespot.apipublicread.cache;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MVP 서울 전용 region → KMA 격자(nx, ny) 변환기.
 * 향후 타 지역 확장 시 이 클래스의 지도 확장만으로 대응 가능.
 */
@Component
public class RegionToGridResolver {

    private static final Map<String, int[]> GRID_MAP = Map.ofEntries(
            Map.entry("서울", new int[]{60, 127}),
            Map.entry("서울특별시", new int[]{60, 127}),
            Map.entry("종로구", new int[]{60, 127}),
            Map.entry("중구", new int[]{60, 127}),
            Map.entry("용산구", new int[]{60, 126}),
            Map.entry("성동구", new int[]{61, 127}),
            Map.entry("광진구", new int[]{62, 126}),
            Map.entry("동대문구", new int[]{61, 127}),
            Map.entry("중랑구", new int[]{62, 127}),
            Map.entry("성북구", new int[]{61, 127}),
            Map.entry("강북구", new int[]{61, 128}),
            Map.entry("도봉구", new int[]{61, 129}),
            Map.entry("노원구", new int[]{61, 129}),
            Map.entry("은평구", new int[]{59, 127}),
            Map.entry("서대문구", new int[]{59, 127}),
            Map.entry("마포구", new int[]{59, 127}),
            Map.entry("양천구", new int[]{58, 126}),
            Map.entry("강서구", new int[]{57, 126}),
            Map.entry("구로구", new int[]{58, 125}),
            Map.entry("금천구", new int[]{59, 124}),
            Map.entry("영등포구", new int[]{59, 126}),
            Map.entry("동작구", new int[]{59, 125}),
            Map.entry("관악구", new int[]{59, 125}),
            Map.entry("서초구", new int[]{61, 125}),
            Map.entry("강남구", new int[]{61, 126}),
            Map.entry("송파구", new int[]{62, 126}),
            Map.entry("강동구", new int[]{62, 127})
    );

    private static final Set<String> GRID_SET = GRID_MAP.values().stream()
            .map(g -> g[0] + ":" + g[1])
            .collect(Collectors.toUnmodifiableSet());

    public Optional<int[]> resolve(String region) {
        if (region == null) return Optional.empty();
        return Optional.ofNullable(GRID_MAP.get(region));
    }

    public boolean isSupported(String region) {
        return GRID_MAP.containsKey(region);
    }

    public boolean isSupportedGrid(int nx, int ny) {
        return GRID_SET.contains(nx + ":" + ny);
    }
}
