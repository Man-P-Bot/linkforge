package com.linkforge.core.infrastructure.persistence;

import com.linkforge.core.repository.StatsRepository;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * {@link StatsRepository} 的 MyBatis 实现（docs/DECISIONS.md DR-021 的同构做法）。
 *
 * <p>⚠️ <b>Repository 与 Mapper 的 record/POJO 转换在这一层完成</b>：
 * 上层（stats / 未来的 admin）用不可变 record，Mapper 侧用带 setter 的可变 POJO
 * （MyBatis 的默认对象映射走 setter，record 的访问器不被识别——
 * 同样的坑在 {@link LinkMapper.IdShortCode} 上已经踩过一次，
 * 结果是<b>静默返回 null</b>而不是报错）。
 */
@Repository
public class StatsRepositoryImpl implements StatsRepository {

    private final StatsMapper statsMapper;

    public StatsRepositoryImpl(StatsMapper statsMapper) {
        this.statsMapper = statsMapper;
    }

    @Override
    public int accumulatePv(Collection<PvRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<StatsMapper.PvRow> mapperRows = rows.stream()
                .map(r -> {
                    StatsMapper.PvRow m = new StatsMapper.PvRow();
                    m.setLinkId(r.linkId());
                    m.setStatDate(r.statDate());
                    m.setDelta(r.delta());
                    return m;
                })
                .toList();
        return statsMapper.accumulatePvBatch(mapperRows);
    }

    @Override
    public int mergeUv(Collection<UvRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<StatsMapper.UvRow> mapperRows = rows.stream()
                .map(r -> {
                    StatsMapper.UvRow m = new StatsMapper.UvRow();
                    m.setLinkId(r.linkId());
                    m.setStatDate(r.statDate());
                    m.setEstimate(r.estimate());
                    return m;
                })
                .toList();
        return statsMapper.mergeUvBatch(mapperRows);
    }
}
