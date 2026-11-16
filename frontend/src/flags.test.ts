import { describe, expect, it } from "vitest";
import { flagCode } from "./flags";

describe("flagCode", () => {
  it("лиги -> флаги стран", () => {
    expect(flagCode("soccer_epl")).toBe("gb-eng");
    expect(flagCode("soccer_efl_champ")).toBe("gb-eng");
    expect(flagCode("soccer_england_league1")).toBe("gb-eng");
    expect(flagCode("soccer_russia_premier_league")).toBe("ru");
    expect(flagCode("soccer_spl")).toBe("gb-sct");
    expect(flagCode("soccer_uefa_nations_league")).toBe("eu");
    expect(flagCode("soccer_usa_mls")).toBe("us");
    expect(flagCode("soccer_conmebol_copa_libertadores")).toBeNull();
  });
});
