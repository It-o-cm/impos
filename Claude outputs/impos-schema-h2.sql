-- impos database schema (H2 dialect)
-- Generated from the JPA entity classes with Hibernate ORM 7.1.11.Final,
-- dialect org.hibernate.dialect.H2Dialect, target H2 2.4.240.
-- Source: com.intermarche.pos.domain, 56 mapped classes (@Entity and @Embeddable).
-- The script was also executed against an in-memory H2 database, so every
-- statement below is known to be accepted as written.
--
-- 47 tables, 44 identity sequences, 24 foreign keys,
-- 33 unique columns and 2 composite unique constraints, 28 indexes.
--
-- Inheritance: ticket_payments is a SINGLE_TABLE hierarchy discriminated by
-- payment_method (CASH, CARD, CHEQUE, CREDIT, FIDELITY, DEVISE, TR, VOUCHER,
-- SECOURS, ARRONDI); every column contributed by a subclass is nullable.

    create sequence account_customers_SEQ start with 1 increment by 50;

    create sequence article_attribute_definitions_SEQ start with 1 increment by 50;

    create sequence article_barcode_range_SEQ start with 1 increment by 50;

    create sequence article_selections_SEQ start with 1 increment by 50;

    create sequence balance_ticket_lines_SEQ start with 1 increment by 50;

    create sequence balance_tickets_SEQ start with 1 increment by 50;

    create sequence cash_movements_SEQ start with 1 increment by 50;

    create sequence cash_sessions_SEQ start with 1 increment by 50;

    create sequence checkout_island_SEQ start with 1 increment by 50;

    create sequence countries_SEQ start with 1 increment by 50;

    create sequence coupon_control_SEQ start with 1 increment by 50;

    create sequence coupon_field_SEQ start with 1 increment by 50;

    create sequence coupon_scan_SEQ start with 1 increment by 50;

    create sequence coupon_type_SEQ start with 1 increment by 50;

    create sequence currencies_SEQ start with 1 increment by 50;

    create sequence document_counters_SEQ start with 1 increment by 50;

    create sequence document_template_SEQ start with 1 increment by 50;

    create sequence echelon_settings_SEQ start with 1 increment by 50;

    create sequence employee_profiles_SEQ start with 1 increment by 50;

    create sequence employees_SEQ start with 1 increment by 50;

    create sequence engine_feeds_SEQ start with 1 increment by 50;

    create sequence enseignes_SEQ start with 1 increment by 50;

    create sequence fid_events_SEQ start with 1 increment by 50;

    create sequence invoices_SEQ start with 1 increment by 50;

    create sequence pdvs_SEQ start with 1 increment by 50;

    create sequence pos_settings_SEQ start with 1 increment by 50;

    create sequence prices_SEQ start with 1 increment by 50;

    create sequence product_families_SEQ start with 1 increment by 50;

    create sequence products_SEQ start with 1 increment by 50;

    create sequence profiles_SEQ start with 1 increment by 50;

    create sequence ref_states_SEQ start with 1 increment by 50;

    create sequence refund_lines_SEQ start with 1 increment by 50;

    create sequence refunds_SEQ start with 1 increment by 50;

    create sequence stored_values_SEQ start with 1 increment by 50;

    create sequence stores_SEQ start with 1 increment by 50;

    create sequence sync_outbox_SEQ start with 1 increment by 50;

    create sequence technical_events_SEQ start with 1 increment by 50;

    create sequence tender_definition_SEQ start with 1 increment by 50;

    create sequence ticket_counters_SEQ start with 1 increment by 50;

    create sequence ticket_line_valuations_SEQ start with 1 increment by 50;

    create sequence ticket_lines_SEQ start with 1 increment by 50;

    create sequence ticket_payments_SEQ start with 1 increment by 50;

    create sequence tickets_SEQ start with 1 increment by 50;

    create sequence user_favorites_SEQ start with 1 increment by 50;

    create table account_customers (
        checksum integer,
        credit_balance numeric(12,2) not null,
        credit_limit numeric(12,2),
        latitude float(53),
        longitude float(53),
        created_at timestamp(6) not null,
        id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        siret varchar(20),
        vat_number varchar(20),
        account_number varchar(30) not null unique,
        phone varchar(30),
        first_name varchar(60),
        last_name varchar(60),
        company_name varchar(120) not null,
        email varchar(120),
        city varchar(255),
        country varchar(255),
        postalCode varchar(255),
        streetLine1 varchar(255),
        streetLine2 varchar(255),
        primary key (id)
    );

    create table article_attribute_definitions (
        checksum integer,
        created_at timestamp(6) not null,
        id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        code varchar(50) not null unique,
        label varchar(100) not null,
        primary key (id)
    );

    create table article_barcode_range (
        active boolean not null,
        article_length integer not null,
        article_position integer not null,
        check_digit boolean not null,
        code_length integer not null,
        priority integer not null,
        value_decimals integer not null,
        value_length integer not null,
        value_position integer not null,
        id bigint not null,
        prefix varchar(40) not null,
        code varchar(50) not null unique,
        label varchar(100) not null,
        match_pattern varchar(255) not null,
        code_kind enum ('ALPHANUMERIC','ANY','NUMERIC') not null,
        currency enum ('EUR','FRF') not null,
        value_source enum ('PRICE','WEIGHT') not null,
        primary key (id)
    );

    create table article_selections (
        checksum integer,
        created_at timestamp(6) not null,
        id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        name varchar(100) not null unique,
        criteria varchar(500),
        primary key (id)
    );

    create table balance_ticket_lines (
        quantity numeric(10,3) not null,
        total_including_tax numeric(10,2) not null,
        vat_rate numeric(6,4),
        balance_ticket_id bigint,
        id bigint not null,
        ean varchar(20) not null,
        label varchar(80) not null,
        primary key (id)
    );

    create table balance_tickets (
        consumed_at timestamp(6),
        emitted_at timestamp(6),
        id bigint not null,
        consumed_by_terminal varchar(20),
        reference varchar(32) not null unique,
        counter_label varchar(40),
        primary key (id)
    );

    create table cash_movements (
        amount numeric(19,4),
        checksum integer,
        movement_type tinyint not null check ((movement_type between 0 and 5)),
        cashier_id bigint,
        created_at timestamp(6) not null,
        id bigint not null,
        movement_date timestamp(6) not null,
        session_id bigint,
        updated_at timestamp(6) not null,
        version bigint,
        endorsed_by varchar(20),
        terminal_id varchar(20) not null,
        payment_method varchar(30),
        transfer_to varchar(30),
        movement_uid varchar(36) unique,
        denomination_detail varchar(2000),
        reason varchar(255),
        primary key (id)
    );

    create table cash_sessions (
        checksum integer,
        counted_amount numeric(19,4),
        opening_float numeric(19,4) not null,
        status tinyint not null check ((status between 0 and 1)),
        theoretical_amount numeric(19,4),
        variance numeric(19,4),
        withdrawn_amount numeric(19,4),
        closing_cashier_id bigint,
        closing_date timestamp(6),
        created_at timestamp(6) not null,
        id bigint not null,
        opening_cashier_id bigint not null,
        opening_date timestamp(6) not null,
        updated_at timestamp(6) not null,
        version bigint,
        terminal_id varchar(20) not null,
        session_number varchar(30) not null unique,
        count_detail varchar(1000),
        primary key (id)
    );

    create table checkout_island (
        active boolean not null,
        id bigint not null,
        code varchar(50) not null unique,
        label varchar(100) not null,
        terminal_ids varchar(1000),
        primary key (id)
    );

    create table countries (
        checksum integer,
        code varchar(8) not null unique,
        created_at timestamp(6) not null,
        default_language varchar(8),
        id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        name varchar(120) not null,
        primary key (id)
    );

    create table coupon_control (
        coupon_type_id bigint not null,
        id bigint not null,
        message varchar(120),
        kind enum ('DUPLICATE','EXPIRED','FIDELITY_MISMATCH','FIDELITY_REQUIRED','MIN_TICKET_TOTAL','NOT_YET_VALID','OTHER_ISLAND','OTHER_STORE','STORE_CHECK') not null,
        level enum ('BLOCK','INFO','NONE') not null,
        primary key (id)
    );

    create table coupon_field (
        decimals integer,
        field_length integer not null,
        offset_position integer not null,
        coupon_type_id bigint not null,
        id bigint not null,
        currency enum ('EUR','FRF'),
        date_format enum ('DDD','DDMM','DDMMYY','DDMMYYYY','HHMM','HHMMSS','MMDD','MMDDYY','YYYYMMDD'),
        kind enum ('ALPHANUMERIC','ANY','NUMERIC') not null,
        role enum ('CARD_MATCH','DATE_END','DATE_START','MIN_TICKET_TOTAL','PRICE','QUANTITY','SEQUENCE_NUMBER','STORE_CHECK_DIGIT','STORE_NUMBER','TICKET_NUMBER','TIME','TPV_NUMBER') not null,
        primary key (id)
    );

    create table coupon_scan (
        id bigint not null,
        scanned_at timestamp(6) not null,
        ticket_number varchar(32),
        tpv_number varchar(32),
        type_code varchar(50) not null,
        code varchar(64) not null,
        identity varchar(64) not null,
        primary key (id)
    );

    create table coupon_type (
        active boolean not null,
        code_length integer,
        deposit_line boolean not null,
        manual_amount_on_all_nines boolean not null,
        priority integer not null,
        id bigint not null,
        prefix varchar(40),
        code varchar(50) not null unique,
        label varchar(100) not null,
        island_codes varchar(1000),
        amount_pattern varchar(255),
        match_pattern varchar(255) not null,
        amount_source enum ('ENCODED','MANUAL','REGISTRY') not null,
        code_kind enum ('ALPHANUMERIC','ANY','NUMERIC'),
        primary key (id)
    );

    create table currencies (
        active boolean not null,
        code varchar(3) not null unique,
        display_order integer not null,
        euro_per_unit numeric(12,6) not null,
        symbol varchar(5),
        id bigint not null,
        label varchar(40) not null,
        primary key (id)
    );

    create table document_counters (
        id bigint not null,
        last_number bigint not null,
        terminal_id varchar(20) not null,
        document_type enum ('BON_LIVRAISON','FACTURE') not null,
        primary key (id),
        constraint idx_document_counter_terminal_type unique (terminal_id, document_type)
    );

    create table document_template (
        active boolean not null,
        copies integer not null,
        priority integer not null,
        width integer not null,
        id bigint not null,
        code varchar(50) not null unique,
        label varchar(100) not null,
        document_type enum ('CARD_RECEIPT','CREDIT_NOTE_VOUCHER','GIFT_CARD_VOUCHER','INVOICE','INVOICE_A4','REFUND_RECEIPT','SALE_RECEIPT','TRANSFER_TICKET','WITHDRAWAL_TICKET','X_REPORT','Z_REPORT') not null,
        source clob not null,
        primary key (id)
    );

    create table echelon_settings (
        checksum integer,
        effective_date date,
        created_at timestamp(6) not null,
        id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        echelon_code varchar(32) not null,
        setting_key varchar(64) not null,
        setting_value varchar(255) not null,
        echelon_level enum ('COUNTRY','ENSEIGNE','PDV') not null,
        primary key (id),
        constraint uk_echelon_setting unique (echelon_level, echelon_code, setting_key)
    );

    create table employee_profiles (
        checksum integer,
        created_at timestamp(6) not null,
        employee_id bigint not null,
        id bigint not null,
        profile_id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        echelon_scope varchar(20) not null,
        primary key (id)
    );

    create table employees (
        checksum integer,
        failed_attempts integer not null,
        is_active boolean not null,
        must_change_password boolean not null,
        created_at timestamp(6) not null,
        id bigint not null,
        locked_until timestamp(6),
        updated_at timestamp(6) not null,
        version bigint,
        badge_id varchar(20) not null unique,
        theme varchar(20),
        bo_password varchar(100),
        email varchar(255) not null unique,
        first_name varchar(255) not null,
        last_name varchar(255) not null,
        login_name varchar(255) not null,
        password varchar(255) not null,
        role enum ('ADMIN','CASHIER','MANAGER','PICKER') not null,
        primary key (id)
    );

    create table engine_feeds (
        applied_at timestamp(6),
        id bigint not null,
        received_at timestamp(6) not null,
        code varchar(40) not null unique,
        applied_version varchar(64),
        version varchar(64) not null,
        last_error varchar(500),
        content clob not null,
        primary key (id)
    );

    create table enseignes (
        checksum integer,
        code varchar(8) not null unique,
        country_code varchar(8),
        created_at timestamp(6) not null,
        default_language varchar(8),
        id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        name varchar(120) not null,
        primary key (id)
    );

    create table fid_events (
        attempts integer not null,
        created_at timestamp(6) not null,
        id bigint not null,
        sent_at timestamp(6),
        last_error varchar(500),
        event_type enum ('TICKET_CLOSED','TICKET_RETURN') not null,
        payload_json clob not null,
        primary key (id)
    );

    create table invoices (
        checksum integer,
        customer_latitude float(53),
        customer_longitude float(53),
        print_count integer not null,
        total_ht numeric(19,4) not null,
        total_ttc numeric(19,4) not null,
        total_vat numeric(19,4) not null,
        created_at timestamp(6) not null,
        customer_id bigint,
        id bigint not null,
        issue_date timestamp(6) not null,
        ticket_id bigint,
        updated_at timestamp(6) not null,
        version bigint,
        customer_siret varchar(20),
        customer_vat_number varchar(20),
        terminal_id varchar(20) not null,
        customer_account_number varchar(30),
        document_number varchar(30) not null unique,
        ticket_number varchar(30) not null,
        customer_contact varchar(120),
        customer_name varchar(180) not null,
        customer_city varchar(255),
        customer_country varchar(255),
        customer_postal_code varchar(255),
        customer_street1 varchar(255),
        customer_street2 varchar(255),
        document_type enum ('BON_LIVRAISON','FACTURE') not null,
        primary key (id)
    );

    create table pdvs (
        active boolean not null,
        checksum integer,
        pdv_number varchar(5) not null unique,
        created_at timestamp(6) not null,
        enseigne_code varchar(8),
        id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        adherent_code varchar(32),
        name varchar(120) not null,
        primary key (id)
    );

    create table pos_settings (
        id bigint not null,
        setting_key varchar(64) not null unique,
        setting_value varchar(255) not null,
        primary key (id)
    );

    create table prices (
        checksum integer,
        price_excluding_tax numeric(19,4) not null,
        price_including_tax numeric(19,4) not null,
        priority integer not null,
        vat_rate numeric(5,4) not null,
        created_at timestamp(6) not null,
        end_date_time timestamp(6),
        id bigint not null,
        product_id bigint not null,
        start_date_time timestamp(6),
        updated_at timestamp(6) not null,
        version bigint,
        primary key (id)
    );

    create table product_attributes (
        product_id bigint not null,
        attr_code varchar(50) not null,
        attr_value varchar(255),
        primary key (product_id, attr_code)
    );

    create table product_families (
        checksum integer,
        display_order integer not null,
        pinned boolean not null,
        created_at timestamp(6) not null,
        id bigint not null,
        parent_product_family_id bigint,
        sales_volume bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        button_size varchar(10) not null,
        code varchar(50) not null unique,
        flags varchar(1000),
        description varchar(255),
        primary key (id)
    );

    create table product_families_products (
        ProductFamily_id bigint not null,
        products_id bigint not null,
        primary key (ProductFamily_id, products_id)
    );

    create table products (
        age_restriction integer,
        checksum integer,
        forbidden_to_sale boolean not null,
        gift_card_amount numeric(10,2),
        is_active boolean not null,
        plu varchar(4) unique,
        reference_volume numeric(10,3),
        reference_weight numeric(10,3),
        variable_weight boolean,
        created_at timestamp(6) not null,
        id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        icon varchar(10),
        ean varchar(13) not null unique,
        unit_name varchar(20),
        internal_code varchar(50) unique,
        brand varchar(100),
        checkout_label varchar(100),
        description varchar(255),
        name varchar(255) not null,
        image_data clob,
        productType enum ('UNIT','VOLUME','WEIGHT') not null,
        primary key (id)
    );

    create table profile_grants (
        requires_validation boolean not null,
        profile_id bigint not null,
        validating_profile_id bigint,
        feature_key varchar(40) not null,
        crud_option enum ('CREATE','DELETE','UPDATE','VIEW') not null
    );

    create table profiles (
        checksum integer,
        created_at timestamp(6) not null,
        id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        name varchar(80) not null unique,
        description varchar(255),
        primary key (id)
    );

    create table ref_states (
        applied_at timestamp(6) not null,
        id bigint not null,
        domain varchar(20) not null unique,
        fingerprint varchar(64) not null,
        primary key (id)
    );

    create table refund_lines (
        checksum integer,
        price numeric(19,4) not null,
        quantity numeric(10,3) not null,
        vat_rate numeric(5,4),
        created_at timestamp(6) not null,
        id bigint not null,
        original_line_id bigint not null,
        refund_id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        product_label varchar(255) not null,
        primary key (id)
    );

    create table refunds (
        checksum integer,
        status tinyint not null check ((status between 0 and 1)),
        total_amount numeric(19,4) not null,
        total_ht numeric(19,4),
        total_vat numeric(19,4),
        created_at timestamp(6) not null,
        creation_date timestamp(6) not null,
        id bigint not null,
        original_ticket_id bigint not null,
        session_id bigint,
        updated_at timestamp(6) not null,
        version bigint,
        terminal_id varchar(20),
        refund_number varchar(30) unique,
        refund_method enum ('CARD','CASH','LOYALTY','VOUCHER'),
        primary key (id)
    );

    create table stored_values (
        balance numeric(10,2) not null,
        initial_amount numeric(10,2) not null,
        exhausted_at timestamp(6),
        id bigint not null,
        issued_at timestamp(6) not null,
        issuing_refund_id bigint,
        issuing_ticket_id bigint,
        last_redeemed_ticket_id bigint,
        number varchar(20) not null unique,
        kind enum ('CREDIT_NOTE','GIFT_CARD') not null,
        status enum ('ACTIVE','EXHAUSTED') not null,
        primary key (id)
    );

    create table stores (
        address_latitude float(53),
        address_longitude float(53),
        checksum integer,
        share_capital numeric(19,2),
        created_at timestamp(6) not null,
        id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        code varchar(20) not null unique,
        theme varchar(20),
        fax varchar(32),
        phone varchar(32),
        siret varchar(32),
        vat_number varchar(32),
        bank_account_number varchar(64),
        rcs varchar(64),
        legal_name varchar(120),
        address_city varchar(255),
        address_country varchar(255),
        address_postal_code varchar(255),
        address_street_line1 varchar(255),
        address_street_line2 varchar(255),
        name varchar(255) not null,
        primary key (id)
    );

    create table sync_outbox (
        attempts integer not null,
        entity_type tinyint not null check ((entity_type between 0 and 5)),
        created_at timestamp(6) not null,
        entity_id bigint not null,
        id bigint not null,
        last_error varchar(255),
        primary key (id)
    );

    create table technical_events (
        event_type tinyint not null check ((event_type between 0 and 26)),
        event_date timestamp(6) not null,
        id bigint not null,
        operator_badge_id varchar(20),
        terminal_id varchar(20) not null,
        event_uid varchar(36) unique,
        detail varchar(255),
        primary key (id)
    );

    create table tender_definition (
        active boolean not null,
        automatic_withdrawal boolean not null,
        bank_deposit boolean not null,
        cashier_declaration boolean not null,
        change_allowed boolean not null,
        defaults_to_total boolean not null,
        display_order integer not null,
        fidelity_reported boolean not null,
        float_allowed boolean not null,
        max_amount numeric(19,4),
        max_change_amount numeric(19,4),
        max_count integer,
        min_amount numeric(19,4),
        movement_allowed boolean not null,
        refund_allowed boolean not null,
        second_max_amount numeric(19,4),
        withdrawal_report_detail boolean not null,
        id bigint not null,
        functional_id varchar(10) not null unique,
        change_tender_code varchar(20),
        code varchar(20) not null unique,
        label varchar(60) not null,
        drawer_opening enum ('AFTER_PAYMENT','AFTER_RECEIPT','IF_CHANGE_DUE','NEVER') not null,
        max_amount_control enum ('BLOCKING','INFO','NONE','SUPERVISOR','WARNING') not null,
        max_change_control enum ('BLOCKING','INFO','NONE','SUPERVISOR','WARNING') not null,
        max_count_control enum ('BLOCKING','INFO','NONE','SUPERVISOR','WARNING') not null,
        min_amount_control enum ('BLOCKING','INFO','NONE','SUPERVISOR','WARNING') not null,
        second_max_amount_control enum ('BLOCKING','INFO','NONE','SUPERVISOR','WARNING') not null,
        primary key (id)
    );

    create table ticket_counters (
        grand_total numeric(19,4) not null,
        id bigint not null,
        last_customer_number bigint not null,
        last_number bigint not null,
        last_refund_number bigint not null,
        last_session_number bigint not null,
        terminal_id varchar(20) not null unique,
        last_signature varchar(64),
        primary key (id)
    );

    create table ticket_line_valuations (
        advantage_amount numeric(19,2),
        local_total numeric(19,2) not null,
        valued_total numeric(19,2) not null,
        id bigint not null,
        ticket_id bigint not null,
        line_uid varchar(36) not null,
        advantage_label varchar(200),
        offer_label varchar(200),
        primary key (id)
    );

    create table ticket_lines (
        cancelled boolean not null,
        checksum integer,
        discount_forbidden boolean,
        gs1_expiry_date date,
        is_deposit boolean,
        line_number integer not null,
        modifier_value numeric(19,4),
        money_product boolean,
        original_unit_price numeric(19,4),
        price_embedded boolean,
        quantity numeric(10,3) not null,
        to_collect boolean not null,
        total_price numeric(19,4) not null,
        unit_price numeric(19,4) not null,
        vat_rate numeric(5,4) not null,
        cancellation_date timestamp(6),
        created_at timestamp(6) not null,
        id bigint not null,
        product_id bigint,
        ticket_id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        plu varchar(10),
        ean varchar(13),
        cancelled_by varchar(20),
        unit_name varchar(20),
        line_uid varchar(36),
        family_code varchar(50),
        modifier_label varchar(50),
        restricted_tenders varchar(200),
        gs1_data varchar(500),
        family_label varchar(255),
        product_label varchar(255) not null,
        modifier_type enum ('DISCOUNT','FORCE_PRICE','GLOBAL_DISCOUNT','GLOBAL_REMISE','QUANTITY','REMISE'),
        primary key (id)
    );

    create table ticket_payments (
        amount numeric(19,4) not null,
        backup_manual boolean,
        card_degraded_mode boolean default false,
        checksum integer,
        credit_over_limit boolean,
        currency_amount numeric(12,2),
        currency_code varchar(3),
        currency_rate numeric(12,6),
        payment_index integer not null,
        tendered_amount numeric(19,4),
        created_at timestamp(6) not null,
        id bigint not null,
        ticket_id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        payment_method varchar(20) not null check ((payment_method in ('SECOURS','CARD','CASH','CHEQUE','CREDIT','FIDELITY','DEVISE','ARRONDI','TR','VOUCHER'))),
        credit_account_number varchar(30),
        card_auth_number varchar(32),
        backup_method_label varchar(40),
        backup_transaction varchar(40),
        cheque_micr_line varchar(64),
        voucher_number varchar(64),
        voucher_label varchar(100),
        credit_account_name varchar(120),
        primary key (id),
        check (payment_method <> 'SECOURS' or (backup_manual is not null)),
        check (payment_method <> 'CARD' or (card_degraded_mode is not null)),
        check (payment_method <> 'CREDIT' or (credit_over_limit is not null))
    );

    create table tickets (
        checksum integer,
        global_discount_applied numeric(10,2),
        global_discount_value numeric(10,2),
        grand_total numeric(19,4),
        item_count integer not null,
        print_count integer not null,
        status tinyint not null check ((status between 0 and 3)),
        total_ht numeric(19,4) not null,
        total_ttc numeric(19,4) not null,
        total_vat numeric(19,4) not null,
        valuation_status tinyint not null check ((valuation_status between 0 and 2)),
        cashier_id bigint not null,
        closing_date timestamp(6),
        created_at timestamp(6) not null,
        creation_date timestamp(6) not null,
        id bigint not null,
        session_id bigint,
        store_id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        global_discount_type varchar(10),
        digital_key varchar(16),
        terminal_id varchar(20) not null,
        ticket_number varchar(30) not null unique,
        fidelity_card varchar(50),
        previous_signature varchar(64),
        signature varchar(64),
        customer_email varchar(120),
        formatted_content clob,
        primary key (id)
    );

    create table user_favorites (
        checksum integer,
        created_at timestamp(6) not null,
        employee_id bigint not null,
        id bigint not null,
        updated_at timestamp(6) not null,
        version bigint,
        feature_key varchar(40) not null,
        primary key (id)
    );

    create index idx_account_customer_name 
       on account_customers (company_name);

    create index idx_movement_terminal_date 
       on cash_movements (terminal_id, movement_date);

    create index idx_movement_type 
       on cash_movements (movement_type);

    create index idx_session_terminal_status 
       on cash_sessions (terminal_id, status);

    create index idx_coupon_scan_code 
       on coupon_scan (code);

    create index idx_coupon_scan_identity 
       on coupon_scan (type_code, identity);

    create index idx_echelon_setting_key 
       on echelon_settings (setting_key);

    create index idx_emp_profile_employee 
       on employee_profiles (employee_id);

    create index idx_enseigne_country 
       on enseignes (country_code);

    create index idx_invoice_ticket 
       on invoices (ticket_number);

    create index idx_invoice_date 
       on invoices (issue_date);

    create index idx_invoice_customer 
       on invoices (customer_id);

    create index idx_pdv_enseigne 
       on pdvs (enseigne_code);

    create index idx_pdv_adherent 
       on pdvs (adherent_code);

    create index idx_price_validity 
       on prices (product_id, start_date_time, end_date_time);

    create index idx_product_name 
       on products (name);

    create index idx_refund_line_original 
       on refund_lines (original_line_id);

    create index idx_refund_original 
       on refunds (original_ticket_id);

    create index idx_store_code 
       on stores (code);

    create index idx_store_name 
       on stores (name);

    create index idx_sync_outbox_order 
       on sync_outbox (entity_type, id);

    create index idx_tech_event_terminal 
       on technical_events (terminal_id, event_date);

    create index idx_tech_event_type 
       on technical_events (event_type);

    create index idx_line_valuation_ticket 
       on ticket_line_valuations (ticket_id);

    create index idx_ticket_date 
       on tickets (creation_date);

    create index idx_ticket_cashier 
       on tickets (cashier_id);

    create index idx_ticket_terminal_status 
       on tickets (terminal_id, status);

    create index idx_user_favorite_employee 
       on user_favorites (employee_id);

    alter table if exists balance_ticket_lines 
       add constraint FK4l3865ggkvavw4ck54sei8ug3 
       foreign key (balance_ticket_id) 
       references balance_tickets;

    alter table if exists cash_movements 
       add constraint FKjs5ql98ck4di5w2nsg5d01r8h 
       foreign key (cashier_id) 
       references employees;

    alter table if exists cash_movements 
       add constraint FKspc5lh4csn7dnn8hi1gdavak4 
       foreign key (session_id) 
       references cash_sessions;

    alter table if exists cash_sessions 
       add constraint FKjvxk66iecbfiftik57361d8u9 
       foreign key (closing_cashier_id) 
       references employees;

    alter table if exists cash_sessions 
       add constraint FKclnhbajkcr1tug8aegh04ruj8 
       foreign key (opening_cashier_id) 
       references employees;

    alter table if exists coupon_control 
       add constraint FKhou7mk16gghwm8necptfyoojp 
       foreign key (coupon_type_id) 
       references coupon_type;

    alter table if exists coupon_field 
       add constraint FKq3wh4pcnvreaqhe3oh9pvc3y9 
       foreign key (coupon_type_id) 
       references coupon_type;

    alter table if exists invoices 
       add constraint FKpq2isjonhk7awfkmfy2t2rxr1 
       foreign key (customer_id) 
       references account_customers;

    alter table if exists invoices 
       add constraint FK4tlproeacx3eeryut064avlnc 
       foreign key (ticket_id) 
       references tickets;

    alter table if exists prices 
       add constraint FKhpva2t51a39twh6gdkxdcllyf 
       foreign key (product_id) 
       references products;

    alter table if exists product_attributes 
       add constraint FKcex46yvx4g18b2pn09p79h1mc 
       foreign key (product_id) 
       references products;

    alter table if exists product_families 
       add constraint FKakxm4pnjsp1qr77wi2vax8py9 
       foreign key (parent_product_family_id) 
       references product_families;

    alter table if exists product_families_products 
       add constraint FK8ps8gg7itgnfujo0bu5lg8u4f 
       foreign key (products_id) 
       references products;

    alter table if exists product_families_products 
       add constraint FKmhh04um3cnctsbxo984p4pf3e 
       foreign key (ProductFamily_id) 
       references product_families;

    alter table if exists profile_grants 
       add constraint FKos68qt87wmyny8qsbicjbjsqk 
       foreign key (profile_id) 
       references profiles;

    alter table if exists refund_lines 
       add constraint FKfvmn7t1omqcr7x3f1hkrlkxyw 
       foreign key (refund_id) 
       references refunds;

    alter table if exists refunds 
       add constraint FKh8cmo8x7o8cc8c3p8x0n8mlp8 
       foreign key (session_id) 
       references cash_sessions;

    alter table if exists ticket_line_valuations 
       add constraint FKf22g6264g0xv9g5tmae20fq7o 
       foreign key (ticket_id) 
       references tickets;

    alter table if exists ticket_lines 
       add constraint FK8pwb9m5lsruuam8j6ft8axnl4 
       foreign key (product_id) 
       references products;

    alter table if exists ticket_lines 
       add constraint FKhclfcau0wr2q6lunc12miomg 
       foreign key (ticket_id) 
       references tickets;

    alter table if exists ticket_payments 
       add constraint FKqkunerh4gkdeldn0efoo04s23 
       foreign key (ticket_id) 
       references tickets;

    alter table if exists tickets 
       add constraint FK1ahtrmc1y0wg3ln4m1o256qwf 
       foreign key (cashier_id) 
       references employees;

    alter table if exists tickets 
       add constraint FKaytr04linr7lrynf88epx9aq3 
       foreign key (session_id) 
       references cash_sessions;

    alter table if exists tickets 
       add constraint FK3k97im3a0wwgshf6fvgjs9wsl 
       foreign key (store_id) 
       references stores;
