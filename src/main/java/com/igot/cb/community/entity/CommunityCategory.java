package com.igot.cb.community.entity;

import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import java.sql.Timestamp;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TypeDef;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "community_category")
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
@Entity
public class CommunityCategory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY) // Auto-increment strategy
  @Column(name = "category_id")
  private Integer categoryId;

  @Column(name = "category_name", nullable = false)
  private String categoryName;

  @Column(name = "description")
  private String description;

  @Column(name = "parent_id")
  private Integer parentId;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive = true; // Default value

  @Column(name = "created_at")
  private Timestamp createdAt;

  @Column(name = "last_updated_at")
  private Timestamp lastUpdatedAt;

  @Column(name = "department_id")
  private String departmentId;

}
