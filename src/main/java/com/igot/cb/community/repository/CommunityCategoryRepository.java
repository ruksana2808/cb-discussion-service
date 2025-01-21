package com.igot.cb.community.repository;

import com.igot.cb.community.entity.CommunityCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityCategoryRepository extends JpaRepository<CommunityCategory, Integer> {

  CommunityCategory findByCategoryIdAndIsActive(Integer categoryId, boolean isActive);

  CommunityCategory findByParentIdAndCategoryNameAndIsActive(int parentId, String categoryName, boolean isActive);

  CommunityCategory findByCategoryNameAndIsActive(String categoryName, boolean isActive);

  List<CommunityCategory> findByParentIdAndIsActive(int parentId, boolean isActive);

  CommunityCategory findByParentIdAndCategoryNameAndDepartmentIdAndIsActive(int parentId, String categoryName, String departmentId, boolean isActive);
}
