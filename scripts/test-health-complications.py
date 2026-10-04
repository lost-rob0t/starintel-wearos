#!/usr/bin/env python3
"""Evaluate the checked-in WFF gauge expressions with real provider-shaped inputs."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

class HealthComplications(unittest.TestCase):
    def test_health_gauges(self):
        checked = 0
        for face in ("neon", "command", "terminal"):
            root=ET.parse(Path("watchface/src")/face/"res/raw/watchface.xml").getroot()
            for slot in root.findall(".//ComplicationSlot"):
                renderer=slot.find("Complication[@type='RANGED_VALUE']")
                if renderer is None: continue
                text=ET.tostring(renderer,encoding="unicode")
                self.assertIn("COMPLICATION.TITLE",text)
                self.assertIn("%.0f",text) # numeric-only providers have a visible value
                transforms=[t for t in renderer.findall(".//Transform") if "RANGED_VALUE" in t.get("value","")]
                self.assertTrue(transforms)
                for transform in transforms:
                    expression=transform.get("value")
                    for value,minimum,maximum in ((78,40,200),(7842,0,10000),(0,0,100),(100,0,100),(-10,0,100),(150,0,100),(1,1,1),(1,10,0)):
                        with self.subTest(face=face,slot=slot.get("slotId"),value=value,min=minimum,max=maximum):
                            e=expression
                            for name,v in (("VALUE",value),("MIN",minimum),("MAX",maximum)):
                                e=e.replace("[COMPLICATION.RANGED_VALUE_"+name+"]",str(v))
                            condition, branches=e.split("?",1); yes,no=branches.split(":",1)
                            result=eval(yes if eval(condition,{"__builtins__":{}},{}) else no,{"__builtins__":{}},{"clamp":lambda n,a,b:max(a,min(b,n))})
                            self.assertTrue(isinstance(result,(float,int)))
                            base = float(yes.strip())
                            span = float(re.search(r"\*\s*(\d+(?:\.\d+)?)\s*$", no).group(1))
                            ratio = 0 if maximum <= minimum else max(0, min(1, (value-minimum)/(maximum-minimum)))
                            self.assertAlmostEqual(result, base + ratio * span)
                            self.assertGreaterEqual(result, base)
                            self.assertLessEqual(result, base + span)
                            checked+=1
        self.assertGreaterEqual(checked,100)
        print(f"health gauge fixtures: {checked}")

if __name__ == "__main__": unittest.main()
