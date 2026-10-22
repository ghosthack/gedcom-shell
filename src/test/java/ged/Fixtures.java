package ged;

import ged.model.Genealogy;

/// Small hand-built tree used across tests.
///
/// ```
///   Giovanni Rossi (1820) ∞ Lucia Bianchi (1825)          [F1]
///          │
///   ┌──────┴────────────┐
///   Marco (1850)     Anna (1852)
///   ∞ José Pérez [F2]   ∞ Paolo Verdi [F3]
///   │                   │
///   Luca (1880)  ∞  Sofia (1882)    ← first cousins     [F4]
///          │
///        Elena (1910)
/// ```
public final class Fixtures {
    private Fixtures() {}

    public static final String COUSINS = """
            0 HEAD
            1 CHAR UTF-8
            1 GEDC
            2 VERS 5.5.1
            0 @I1@ INDI
            1 NAME Giovanni /Rossi/
            1 SEX M
            1 BIRT
            2 DATE 3 MAR 1820
            2 PLAC Genova, Italia
            1 DEAT
            2 DATE ABT 1890
            1 FAMS @F1@
            0 @I2@ INDI
            1 NAME Lucia /Bianchi/
            1 SEX F
            1 BIRT
            2 DATE 1825
            1 FAMS @F1@
            0 @I3@ INDI
            1 NAME Marco /Rossi/
            1 SEX M
            1 BIRT
            2 DATE 12 JUN 1850
            2 PLAC Genova, Italia
            1 OCCU Sailor
            1 FAMC @F1@
            1 FAMS @F2@
            1 NOTE @N1@
            1 CHAN
            2 DATE 16 JUN 2007
            3 TIME 17:07:01
            0 @I4@ INDI
            1 NAME Anna /Rossi/
            1 SEX F
            1 BIRT
            2 DATE 1852
            1 FAMC @F1@
            1 FAMS @F3@
            0 @I5@ INDI
            1 NAME José /Pérez/
            1 SEX F
            1 BIRT
            2 DATE 1855
            2 PLAC Sevilla, España
            1 FAMS @F2@
            0 @I6@ INDI
            1 NAME Paolo /Verdi/
            1 SEX M
            1 FAMS @F3@
            0 @I7@ INDI
            1 NAME Luca /Rossi/
            1 SEX M
            1 BIRT
            2 DATE 1880
            1 FAMC @F2@
            1 FAMS @F4@
            0 @I8@ INDI
            1 NAME Sofia /Verdi/
            1 SEX F
            1 BIRT
            2 DATE 1882
            1 FAMC @F3@
            1 FAMS @F4@
            0 @I9@ INDI
            1 NAME Elena /Rossi/
            1 SEX F
            1 BIRT
            2 DATE 7 JAN 1910
            1 DEAT
            2 DATE 2 JAN 1990
            1 FAMC @F4@
            0 @F1@ FAM
            1 HUSB @I1@
            1 WIFE @I2@
            1 CHIL @I3@
            1 CHIL @I4@
            1 MARR
            2 DATE 1848
            0 @F2@ FAM
            1 HUSB @I3@
            1 WIFE @I5@
            1 CHIL @I7@
            0 @F3@ FAM
            1 HUSB @I6@
            1 WIFE @I4@
            1 CHIL @I8@
            0 @F4@ FAM
            1 HUSB @I7@
            1 WIFE @I8@
            1 CHIL @I9@
            0 @N1@ NOTE Went to sea
            1 CONT at fifteen.
            0 TRLR
            """;

    public static Genealogy cousins() {
        return Genealogy.of(COUSINS);
    }
}
