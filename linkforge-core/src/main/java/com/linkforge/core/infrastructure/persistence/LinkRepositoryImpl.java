package com.linkforge.core.infrastructure.persistence;

import com.linkforge.core.domain.Link;
import com.linkforge.core.repository.LinkRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link LinkRepository} 的 MyBatis 实现（docs/DECISIONS.md DR-021）。
 *
 * <h2>为什么放在 core，而不是 redirect 或 admin</h2>
 * {@code admin} 删除短链同样要访问 {@code LinkRepository}：
 * 若实现放 {@code redirect}，admin 要么依赖 redirect（违反 DR-018 的双向隔离），
 * 要么看不到实现（Spring 装配失败）。因此实现必须与接口、Mapper 同处 core。
 *
 * <h2>本类只做「接口 → Mapper」的转发，不含任何业务判定</h2>
 * 具体地说，**失效判定优先级不在这一层**：
 * 「先看 status（DELETED → 404）、再看 expire_at（已过期 → 410）」是**上层用例**的职责
 * （docs/DATABASE.md §4.2），因为 404 与 410 是 HTTP 语义，仓储层不应该知道 HTTP。
 *
 * <h2>⚠️ 时间字段一律是 UTC 的 {@code LocalDateTime}</h2>
 * 入参与返回值都**不做任何时区转换**（DR-027）：转换只发生在 Service 边界
 * （`TimeConverter.toUtc` / `toOffset`）。在 Mapper 或本类里再换算一次，
 * 会与驱动层对 {@code TIMESTAMP} 的解释叠加成双重换算，时间整体偏移数小时。
 * <p>
 * 该约定的前提是**数据库会话时区为 UTC**（DR-032 的
 * {@code connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}）：
 * 正是它让「数据库自己生成的 {@code created_at}」也落在 UTC 上，
 * 本层的「不做转换」才是安全的。
 */
@Repository
public class LinkRepositoryImpl implements LinkRepository {

    private final LinkMapper linkMapper;

    public LinkRepositoryImpl(LinkMapper linkMapper) {
        this.linkMapper = linkMapper;
    }

    @Override
    public Optional<Link> findByShortCode(String shortCode) {
        // 刻意不过滤 status：已删除/已过期的记录也要返回，由上层决定返回 404 还是 410
        return Optional.ofNullable(linkMapper.selectByShortCode(shortCode));
    }

    @Override
    public Optional<Link> findById(long id) {
        return Optional.ofNullable(linkMapper.selectById(id));
    }

    @Override
    public void insert(Link link) {
        // created_at / updated_at 由数据库默认值生成，此处不赋值（DATABASE §4.1）
        linkMapper.insertLink(link);
    }

    @Override
    public int markDeleted(long id) {
        // 只改数据库状态。删缓存是 Cache Aside 的第二步，必须在事务提交后由上层执行
        // （docs/ARCHITECTURE.md §10），不属于仓储职责。
        return linkMapper.updateStatusToDeleted(id);
    }

    @Override
    public Map<String, Long> resolveIdsByShortCodes(Collection<String> shortCodes) {
        if (shortCodes == null || shortCodes.isEmpty()) {
            return Map.of();
        }
        // ⚠️ 必须去重：flush 每分钟对同一批 dirty 短码反复查，
        // 重复项会让 IN 列表无谓膨胀（短码多时可能撞上 max_allowed_packet）。
        // 用 LinkedHashSet 保持首次出现顺序，便于测试断言稳定。
        List<String> distinct = List.copyOf(new LinkedHashSet<>(shortCodes));
        List<LinkMapper.IdShortCode> rows = linkMapper.selectIdsByShortCodes(distinct);
        Map<String, Long> result = new HashMap<>(rows.size() * 2);
        for (LinkMapper.IdShortCode row : rows) {
            result.put(row.getShortCode(), row.getId());
        }
        // 查不到的短码（已物理不存在）不会出现在结果里，由调用方计数
        return result;
    }

    @Override
    public List<String> scanActiveShortCodes(long afterId, int limit) {
        return linkMapper.selectActiveShortCodesAfter(afterId, limit);
    }

    @Override
    public List<IdShortCode> scanActiveWithId(long afterId, int limit) {
        return linkMapper.selectIdAndShortCodeAfter(afterId, limit).stream()
                // ⚠️ Mapper 侧的 IdShortCode 是可变 POJO（MyBatis 需要 setter），
                // 这里转成不可变 record，让上层拿到的对象不可被意外修改
                .map(row -> new IdShortCode(row.getId(), row.getShortCode()))
                .toList();
    }
}
