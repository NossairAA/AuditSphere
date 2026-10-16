package com.auditsphere.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MissionRepository extends JpaRepository<Mission, UUID> {}
