package vio.barie.core.admin.dto;

import lombok.Data;

@Data
public class CreateOrgRequest {
    private String name;
    private String orgCode;
    private String address;
}


