package com.demo.graphql.error.handling.repository;

import com.demo.graphql.error.handling.domain.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LocationRepository extends JpaRepository<Location, String>  {
}
