package com.example.item;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * 문항 검색 조건 → JPA Specification. 레거시는 뷰 {@code v_item_public} 을 읽지만 여기서는 같은 뜻의 조건을
 * 엔티티에 건다(공개 상태 · 단원 코드 · 태그 이름 id 순). 모든 조건은 AND 로 묶는다(BR-02).
 */
final class ItemSearchSpecifications {

    private ItemSearchSpecifications() {
    }

    static Specification<Item> of(ItemSearchCondition condition) {
        List<Specification<Item>> specs = new ArrayList<>();
        specs.add(isPublic());
        if (!condition.keyword().isEmpty()) {
            specs.add(keyword(condition.keyword()));
        }
        if (!condition.unitCode().isEmpty()) {
            specs.add(unitCode(condition.unitCode()));
        }
        specs.add(level(condition.levelFilter()));
        if (!condition.tag().isEmpty()) {
            specs.add(tagName(condition.tag()));
        }
        return Specification.allOf(specs);
    }

    /** 공개({@code status='A'}) 문항만(BR-01). */
    static Specification<Item> isPublic() {
        return (root, query, cb) -> cb.equal(root.get("status"), ItemStatus.ACTIVE);
    }

    /** 제목 또는 지문 부분 일치(BR-05). {@code %} · {@code _} 는 이스케이프하지 않는다(BR-07). */
    static Specification<Item> keyword(String keyword) {
        String pattern = "%" + keyword + "%";
        return (root, query, cb) -> cb.or(cb.like(root.get("title"), pattern), cb.like(root.get("stem"), pattern));
    }

    /** 단원 코드 일치(BR-08). 대소문자 비교는 DB 콜레이션을 따른다(BR-09). */
    static Specification<Item> unitCode(String unitCode) {
        return (root, query, cb) -> cb.equal(root.get("unit").get("code"), unitCode);
    }

    /** 난이도(BR-11 · BR-12). int 범위를 넘는 값은 어떤 문항과도 같지 않으므로 0건 조건이다. */
    static Specification<Item> level(ItemSearchCondition.LevelFilter filter) {
        return (root, query, cb) -> {
            if (filter.mode() == ItemSearchCondition.LevelFilter.Mode.BELOW_MAX) {
                return cb.lessThan(root.get("level"), ItemSearchCondition.LEVEL_MAX);
            }
            if (filter.level().isEmpty()) {
                return cb.disjunction();
            }
            return cb.equal(root.get("level"), filter.level().getAsInt());
        };
    }

    /** 이름이 정확히 같은 태그가 붙은 문항(BR-13 · BR-14). */
    static Specification<Item> tagName(String tag) {
        return (root, query, cb) -> {
            Subquery<Integer> sub = query.subquery(Integer.class);
            Root<Item> tagged = sub.from(Item.class);
            Join<Item, Tag> tags = tagged.join("tags");
            sub.select(tagged.get("id"))
                .where(cb.equal(tagged.get("id"), root.get("id")), cb.equal(tags.get("name"), tag));
            return cb.exists(sub);
        };
    }
}
