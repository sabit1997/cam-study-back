package com.camstudy.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp; // Hibernate의 CreationTimestamp 임포트

import java.time.Instant; // java.time.Instant 임포트

@Entity
@Table(name = "todo_items")
@Getter // 필드의 getter 메서드 자동 생성
@Setter // 필드의 setter 메서드 자동 생성
@NoArgsConstructor // 인자 없는 기본 생성자 자동 생성
@AllArgsConstructor // 모든 필드를 인자로 받는 생성자 자동 생성
@Builder // 빌더 패턴을 사용하여 객체를 생성할 수 있게 함
public class TodoItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "window_id")
    @JsonIgnore // 이 필드는 JSON 직렬화 시 무시됩니다.
    private Window window;

    private String text;

    // [수정] Boolean -> boolean으로 변경했습니다.
    // 기본형(primitive) boolean 타입은 null을 가질 수 없으며, 기본값은 false입니다.
    // Lombok이 isDone() 메서드를 생성합니다.
    private boolean done = false; 

    @CreationTimestamp // [개선] 엔티티가 영속화될 때(최초 저장될 때) 자동으로 현재 UTC 시간이 기록됩니다.
    @Column(nullable = false, updatable = false) // null을 허용하지 않고, 한 번 생성되면 업데이트되지 않도록 설정
    private Instant createdAt;

    // 기존에 수동으로 작성하셨던 getter/setter 메서드는 @Getter, @Setter 어노테이션이 자동으로 생성해주므로,
    // 이 코드에서는 삭제되었습니다.
}