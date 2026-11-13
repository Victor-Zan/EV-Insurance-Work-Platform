CREATE OR REPLACE FUNCTION protect_ocr_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.file_id IS NOT NULL
    AND current_setting('app.workflow_material_removal',true)='on' THEN
  RETURN OLD;
 END IF;
 IF TG_OP='DELETE' OR (NEW.id,NEW.file_id,NEW.intake_id,NEW.provider,NEW.provider_api,NEW.provider_version,NEW.created_by,NEW.created_at,NEW.simulate_failure)
  IS DISTINCT FROM (OLD.id,OLD.file_id,OLD.intake_id,OLD.provider,OLD.provider_api,OLD.provider_version,OLD.created_by,OLD.created_at,OLD.simulate_failure) THEN
  RAISE EXCEPTION 'OCR source and provider provenance are immutable';
 END IF;
 RETURN NEW;
END $$;
