package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Immutable, per-department version of the candidate ranking weights. */
@Entity
@Table(name = "candidate_weight_profiles")
public class CandidateWeightProfileEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Integer id;
    @Column(name = "structure_id", nullable = false) public int structureId;
    @Column(name = "profile_version", nullable = false) public int profileVersion;
    @Column(name = "ability_weight", nullable = false) public int abilityWeight;
    @Column(name = "location_weight", nullable = false) public int locationWeight;
    @Column(name = "workload_weight", nullable = false) public int workloadWeight;
    @Column(name = "continuity_weight", nullable = false) public int continuityWeight;
    @Column(name = "age_weight", nullable = false) public int ageWeight;
    @Column(name = "change_reason", nullable = false) public String changeReason = "";
    @Column(name = "created_by", nullable = false) public String createdBy;
    @Column(name = "created_at", nullable = false) public String createdAt;
}
